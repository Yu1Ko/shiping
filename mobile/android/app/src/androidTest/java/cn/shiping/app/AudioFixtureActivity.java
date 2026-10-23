package cn.shiping.app;

import android.app.Activity;
import android.os.Bundle;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local test signal only; this Activity is packaged exclusively in the test APK. */
public class AudioFixtureActivity extends Activity {
    private AudioTrack track;
    private TextView status;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(36, 260, 36, 36);
        layout.setBackgroundColor(android.graphics.Color.rgb(240, 255, 240));
        status = new TextView(this); status.setText("识屏本地测试音 · 未播放"); layout.addView(status);
        add(layout, "播放允许捕获的媒体音", () -> play(AudioAttributes.USAGE_MEDIA, true));
        add(layout, "播放通话用途音", () -> play(AudioAttributes.USAGE_VOICE_COMMUNICATION, true));
        add(layout, "播放禁止捕获的媒体音", () -> play(AudioAttributes.USAGE_MEDIA, false));
        add(layout, "停止测试音", this::stop);
        TextView question = new TextView(this); question.setTextSize(22); question.setText("截图解题测试\n\n请解释二分查找的思路与时间复杂度。\n\n此页只有本地测试数据。"); layout.addView(question);
        setContentView(layout);
    }
    private void add(LinearLayout layout, String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); layout.addView(b); }
    private void play(int usage, boolean allowed) {
        stop();
        short[] pcm = new short[16000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 16000) * 2400);
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(allowed ? AudioAttributes.ALLOW_CAPTURE_BY_ALL : AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
        track.write(pcm, 0, pcm.length); track.setLoopPoints(0, pcm.length, -1); track.play();
        status.setText("正在播放 · usage=" + usage + " · capture=" + allowed);
    }
    private void stop() { if (track != null) { track.stop(); track.release(); track = null; } if (status != null) status.setText("测试音已停止"); }
    @Override public void onDestroy() { stop(); super.onDestroy(); }
}
