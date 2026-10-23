package cn.shiping.app;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs only as adb shell, never inside the ordinary app process. No network or audio files. */
public final class MeetingAudioBridge {
    private static final String SOCKET = "cn.shiping.app.meeting_audio.v1";

    public static void main(String[] args) {
        int exit = 0;
        try {
            if (Process.myUid() != 2000 || android.os.Build.VERSION.SDK_INT < 33)
                throw new IllegalStateException("Requires adb shell on Android 13+");
            if (args.length != 0) {
                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress(SOCKET));
                    if (socket.getPeerCredentials().getUid() != 2000) throw new SecurityException("Wrong server UID");
                    socket.setSoTimeout(3000);
                    new DataOutputStream(socket.getOutputStream()).writeUTF("stop".equals(args[0]) ? "STOP" : "PING");
                    System.out.println(new DataInputStream(socket.getInputStream()).readUTF());
                }
            } else {
                Looper.prepareMainLooper();
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("systemMain").invoke(null);
                Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
                Context context = new ContextWrapper(system) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public String getPackageName() { return "com.android.shell"; }
                    @Override public String getOpPackageName() { return "com.android.shell"; }
                    @Override public AttributionSource getAttributionSource() {
                        return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
                    }
                };
                // AudioPolicy's legacy AudioRecord constructor uses the process application,
                // not the policy context, when constructing its attribution source.
                java.lang.reflect.Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
                initialApplication.setAccessible(true);
                initialApplication.set(thread, android.app.Instrumentation.newApplication(android.app.Application.class, context));
                int meetingUid = system.getPackageManager().getPackageUid("com.tencent.wemeet.app", 0);
                String key = new String(Files.readAllBytes(Paths.get("/data/local/tmp/shiping-meeting-audio-token")), java.nio.charset.StandardCharsets.US_ASCII).trim();
                if (!key.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid activation credential");
                serve(context, key, meetingUid);
            }
        } catch (Exception e) {
            System.err.println("Meeting audio helper: " + e);
            exit = 1;
        }
        System.exit(exit);
    }

    private static void serve(Context context, String key, int meetingUid) throws Exception {
        // An activation expires even if the app never connects. A reboot also removes it.
        Thread expiry = new Thread(() -> {
            try { Thread.sleep(8 * 60 * 60 * 1000L); System.exit(0); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "meeting-audio-expiry");
        expiry.setDaemon(true); expiry.start();
        AtomicReference<Socket> active = new AtomicReference<>();
        try (LocalServerSocket server = new LocalServerSocket(SOCKET);
             ServerSocket audioServer = new ServerSocket(MeetingAudioAuth.PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread captureWorker = new Thread(() -> {
                while (true) {
                    try (Socket socket = audioServer.accept()) {
                        socket.setSoTimeout(3000);
                        socket.setTcpNoDelay(true);
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                        String nonce = input.readUTF();
                        if (!nonce.matches("[0-9a-f]{64}")) throw new SecurityException("Invalid audio challenge");
                        String serverNonce = MeetingAudioAuth.nonce();
                        output.writeUTF(serverNonce);
                        nonce += serverNonce;
                        output.writeUTF(MeetingAudioAuth.proof(key, "server", nonce));
                        if (!MeetingAudioAuth.matches(MeetingAudioAuth.proof(key, "client", nonce), input.readUTF()))
                            throw new SecurityException("Invalid audio request");
                        active.set(socket);
                        try { stream(context, meetingUid, socket, input, output); }
                        finally { active.compareAndSet(socket, null); }
                    } catch (java.io.IOException e) { /* Disconnected or incomplete authentication. */ }
                    catch (Exception e) { System.err.println("Meeting audio connection: " + e); }
                    try { Thread.sleep(500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }, "meeting-audio-connector");
            captureWorker.setDaemon(true); captureWorker.start();
            System.out.println("READY: Tencent Meeting only; activation expires in 8 hours");
            while (true) {
                try (LocalSocket socket = server.accept()) {
                    int caller = socket.getPeerCredentials().getUid();
                    if (caller != 2000) continue;
                    socket.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    String command = input.readUTF();
                    if ("STOP".equals(command) && caller == 2000) {
                        Socket current = active.get();
                        if (current != null) current.close();
                        output.writeUTF("STOPPED"); return;
                    }
                    if ("PING".equals(command)) { output.writeUTF("READY"); continue; }
                    output.writeUTF("DENIED");
                } catch (Exception e) {
                    System.err.println("Meeting audio control: " + e);
                }
            }
        }
    }

    private static void stream(Context context, int meetingUid, Socket socket, DataInputStream input, DataOutputStream output) {
        try (Capture capture = new Capture(context, meetingUid)) {
            capture.recorder.startRecording();
            if (capture.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not start");
            output.writeUTF("PCM16/16000/1");
            socket.setSoTimeout(0);
            AtomicBoolean connected = new AtomicBoolean(true);
            Thread disconnect = new Thread(() -> {
                try { input.read(); } catch (java.io.IOException ignored) { /* Peer closed. */ }
                connected.set(false);
                capture.stop();
            }, "meeting-audio-disconnect");
            disconnect.setDaemon(true); disconnect.start();
            try {
                byte[] pcm = new byte[3200];
                while (connected.get()) {
                    int count = capture.recorder.read(pcm, 0, pcm.length);
                    if (count < 0) throw new IllegalStateException("Audio read failed: " + count);
                    if (count > 0 && connected.get()) output.write(pcm, 0, count);
                }
            } finally {
                connected.set(false);
                socket.shutdownInput();
                capture.stop();
                disconnect.join(2000);
            }
        } catch (Exception e) {
            System.err.println("Meeting audio session ended: " + e);
        }
    }

    /** Android system AudioMix API: UID restriction AND permitted media/voice usages. */
    private static final class Capture implements AutoCloseable {
        AudioRecord recorder;
        private Object policy;
        private Class<?> policyClass;
        private final AudioManager manager;

        Capture(Context context, int uid) throws Exception {
            manager = context.getSystemService(AudioManager.class);
            try {
                Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
                Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
                Object rules = ruleBuilderClass.getConstructor().newInstance();
                ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(rules, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
                Method add = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
                add.invoke(rules, ruleClass.getField("RULE_MATCH_UID").getInt(null), uid);
                for (int usage : new int[]{AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN}) {
                    add.invoke(rules, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), new AudioAttributes.Builder().setUsage(usage).build());
                }
                ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(rules, true);
                Object rule = ruleBuilderClass.getMethod("build").invoke(rules);
                Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
                Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
                Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
                AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
                mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));
                Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
                policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
                Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
                Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
                policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
                policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
                int result = (int) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy);
                if (result != 0) { policy = null; throw new IllegalStateException("Audio policy denied: " + result); }
                recorder = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
                if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("Meeting audio initialization failed");
            } catch (Exception e) { close(); throw e; }
        }

        synchronized void stop() {
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { /* Already stopped. */ }
            }
        }

        @Override public synchronized void close() {
            stop();
            if (recorder != null) { recorder.release(); recorder = null; }
            if (policy != null) {
                try { AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy); }
                catch (Exception e) {
                    // Binder death removes policies if the platform cannot unregister explicitly.
                    System.err.println("Cannot release capture policy: " + e); System.exit(1);
                }
                finally { policy = null; }
            }
        }
    }
}
