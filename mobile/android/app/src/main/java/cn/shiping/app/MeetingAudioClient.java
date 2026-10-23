package cn.shiping.app;

import android.content.Context;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.File;
import java.nio.file.Files;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Authenticated local connection to the explicitly USB-activated shell helper. */
final class MeetingAudioClient implements AutoCloseable {
    private final Socket socket = new Socket();
    private final File credential;
    private DataInputStream input;

    MeetingAudioClient(Context context) { credential = new File(context.getFilesDir(), "meeting-audio-token"); }

    void start() throws IOException {
        if (android.os.Build.VERSION.SDK_INT < 33) throw new IOException("腾讯会议调试采集需要 Android 13 或更新版本");
        try {
            String key = new String(Files.readAllBytes(credential.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim();
            socket.connect(new InetSocketAddress("127.0.0.1", MeetingAudioAuth.PORT), 3000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            String nonce = MeetingAudioAuth.nonce();
            output.writeUTF(nonce);
            String serverNonce = input.readUTF();
            if (!serverNonce.matches("[0-9a-f]{64}")) throw new IOException("会议音频认证响应无效");
            nonce += serverNonce;
            if (!MeetingAudioAuth.matches(MeetingAudioAuth.proof(key, "server", nonce), input.readUTF()))
                throw new IOException("会议音频服务身份不符，请重新激活");
            output.writeUTF(MeetingAudioAuth.proof(key, "client", nonce));
            String result = input.readUTF();
            if (!"PCM16/16000/1".equals(result)) throw new IOException(result);
        } catch (Exception e) {
            close();
            throw new IOException("腾讯会议音频不可用：请先用电脑运行 activate-meeting-audio.ps1；重启手机后需重新激活。详情：" + e.getMessage(), e);
        }
    }

    int read(byte[] buffer) throws IOException {
        // Fixed frames prevent a socket read from splitting a 16-bit PCM sample.
        input.readFully(buffer);
        return buffer.length;
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { /* Closing also unblocks pending reads. */ }
    }
}
