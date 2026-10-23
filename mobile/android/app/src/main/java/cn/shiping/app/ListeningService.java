package cn.shiping.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Captures only user-authorized audio. No recording files and no microphone fallback. */
public class ListeningService extends Service {
    static volatile boolean active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService audio = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService transport = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private InterviewRuntime runtime;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private AudioRecord recorder;
    private MeetingAudioClient meetingAudio;
    private volatile AsrClient asr;
    private volatile boolean reading, stopping;
    private boolean diagnostic, initialized;
    private String source, purpose;
    private JSONObject config;
    private int retries;
    private InterviewOverlay overlay;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopListening(); return START_NOT_STICKY; }
        if (initialized) return START_NOT_STICKY;
        initialized = true; runtime = InterviewRuntime.get(this);
        source = intent.getStringExtra("source"); purpose = intent.getStringExtra("purpose");
        if (!"microphone".equals(source) && !"meeting".equals(source)) source = "playback";
        if (!"screenshot".equals(purpose)) purpose = "interview";
        diagnostic = intent.getBooleanExtra("diagnostic", false);
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("listening", "连续语音与面试", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 31, new Intent(this, MainActivity.class).setAction("cn.shiping.INTERVIEW"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 32, new Intent(this, ListeningService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "listening").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(diagnostic ? "识屏 · 本地音频检测" : "识屏 · 连续语音识别")
                .setContentText((source.equals("meeting") ? "腾讯会议音频" : source.equals("playback") ? "应用播放音频" : "麦克风") + (diagnostic ? " · 不联网，60 秒后结束" : " · 音频正在发送到所配置的 ASR"))
                .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止监听", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 30) startForeground(31, notification, source.equals("playback") ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else if (Build.VERSION.SDK_INT >= 29 && source.equals("playback")) startForeground(31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(31, notification);
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("尚未授权录音");
            if (source.equals("meeting")) {
                if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("腾讯会议调试采集需要 Android 13 或更新版本");
                meetingAudio = new MeetingAudioClient(this);
            } else {
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min < 0) throw new IllegalStateException("设备不支持 16 kHz PCM 音频采集");
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(min, 6400));
            if (source.equals("playback")) {
                if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("应用音频捕获需要 Android 10 或更新版本");
                projection = ProjectionSession.acquire(this, intent.getIntExtra("result", -1), intent.getParcelableExtra("data"));
                projectionCallback = new MediaProjection.Callback() { @Override public void onStop() { stopListening(); } };
                projection.registerCallback(projectionCallback, main);
                builder.setAudioPlaybackCaptureConfig(new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build());
            } else builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder = builder.build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("音频采集初始化失败");
            }
            active = true;
            runtime.meter(0, "切到目标应用并播放声音，观察音量是否变化");
            if (android.provider.Settings.canDrawOverlays(this)) {
                overlay = new InterviewOverlay(this, runtime, this::stopListening); overlay.show();
            }
            if (diagnostic) { runtime.state("detecting", source, purpose, true, ""); startRecord(); main.postDelayed(this::stopListening, 60000); }
            else {
                runtime.state("connecting", source, purpose, false, "");
                transport.execute(() -> {
                    try {
                        JSONObject options = runtime.configuration().getJSONObject("settings");
                        config = options.getJSONObject("asr");
                        JSONObject interview = options.optJSONObject("interview");
                        InterviewEngine.put(config, "silenceMs", interview == null ? 800 : interview.optInt("silenceMs", 800));
                        connect();
                    } catch (Exception e) { main.post(() -> failed(e.getMessage())); }
                });
            }
        } catch (Exception e) { failed("无法启动连续语音：" + e.getMessage()); }
        return START_NOT_STICKY;
    }
    private void connect() {
        if (stopping) return;
        try {
            AsrClient current = new AsrClient(http, transport, new AsrClient.Listener() {
                public void ready() { main.post(() -> { if (!stopping) { runtime.state("listening", source, purpose, false, ""); startRecord(); } }); }
                public void sentence(String text, boolean end) { runtime.sentence(text, end, purpose); }
                public void ended(String message, boolean retryable) {
                    main.post(() -> {
                        if (stopping) { if (message != null) runtime.state("stopped", source, purpose, diagnostic, message); stopSelf(); return; }
                        if (retryable && retries < 3) {
                            int delay = 1 << retries++;
                            runtime.listeningStopped(purpose);
                            runtime.state("reconnecting", source, purpose, false, message + "；" + delay + " 秒后重连，断线期间的音频不补录");
                            main.postDelayed(() -> transport.execute(ListeningService.this::connect), delay * 1000L);
                        } else failed(message == null ? "ASR 服务已结束，请重新开始" : message);
                    });
                }
            });
            asr = current; current.start(config);
        } catch (Exception e) { main.post(() -> failed(e.getMessage())); }
    }
    private void startRecord() {
        if (reading || stopping) return;
        try {
            if (recorder != null) {
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("系统拒绝音频采集");
            }
            reading = true;
            audio.execute(() -> {
                byte[] buffer = new byte[3200];
                long soundAt = SystemClock.elapsedRealtime(), meterAt = 0;
                try {
                    if (meetingAudio != null) meetingAudio.start();
                    while (reading) {
                        int count = meetingAudio != null ? meetingAudio.read(buffer) : recorder.read(buffer, 0, buffer.length);
                        if (!reading) break;
                        if (count < 0) throw new IllegalStateException("音频读取中断（" + count + "）");
                        if (count == 0) continue;
                        double sum = 0;
                        for (int i = 0; i + 1 < count; i += 2) { short sample = (short) ((buffer[i] & 255) | buffer[i + 1] << 8); sum += (double) sample * sample; }
                        double rms = Math.sqrt(sum / Math.max(1, count / 2)) / 32768.0;
                        long now = SystemClock.elapsedRealtime();
                        if (rms > 0.0005) soundAt = now;
                        if (now - meterAt >= 250) {
                            meterAt = now;
                            String hint = now - soundAt > 8000
                                ? (source.equals("meeting") ? "持续未收到腾讯会议声音，请确认对方正在说话；当前只采集腾讯会议，不采集其他应用或麦克风"
                                    : source.equals("playback") ? "持续未捕获到声音：腾讯会议请改选「腾讯会议（USB 激活）」；普通播放捕获不支持通话音轨" : "持续未收到麦克风声音；通话可能优先占用麦克风")
                                : rms > 0.0005 ? "已检测到可采集声音" : "等待目标应用发声";
                            runtime.meter(Math.min(1, rms * 8), hint);
                        }
                        AsrClient current = asr;
                        if (!diagnostic && current != null) current.audio(buffer, count);
                    }
                } catch (Exception e) { if (!stopping) main.post(() -> failed("音频采集中断：" + e.getMessage())); }
            });
        } catch (Exception e) { failed(e.getMessage()); }
    }
    private void failed(String message) {
        if (runtime != null) runtime.state("stopped", source, purpose, diagnostic, message == null ? "连续语音已停止" : message);
        stopping = true; stopSelf();
    }
    private void stopListening() {
        if (stopping) return;
        stopping = true; reading = false;
        if (meetingAudio != null) meetingAudio.close();
        if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) { /* Already stopped by the system. */ } }
        if (runtime != null) runtime.state("finishing", source, purpose, diagnostic, "");
        AsrClient current = asr;
        if (current == null) stopSelf(); else transport.execute(() -> { if (!current.finish()) main.post(this::stopSelf); });
    }
    @Override public void onDestroy() {
        stopping = true; reading = false; active = false; main.removeCallbacksAndMessages(null);
        if (meetingAudio != null) meetingAudio.close();
        if (overlay != null) overlay.close();
        CaptureService.stopInterviewSession();
        if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) { } }
        audio.execute(() -> { if (recorder != null) recorder.release(); }); audio.shutdown();
        if (projection != null) { projection.unregisterCallback(projectionCallback); ProjectionSession.release(projection); projection = null; }
        transport.execute(() -> { if (asr != null) asr.cancel(); http.dispatcher().cancelAll(); http.connectionPool().evictAll(); });
        transport.shutdown();
        if (runtime != null) {
            runtime.listeningStopped(purpose);
            // Preserve errors already reported by failed() or the ASR completion callback.
            runtime.command("snapshot", new JSONObject(), (value, failure) -> {
                JSONObject state = (JSONObject) value;
                runtime.state("stopped", source, purpose, diagnostic, state == null ? "" : state.optString("error"));
            });
        }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
