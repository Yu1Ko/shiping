package cn.shiping.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/** A visible, draggable cue panel; it makes no screen-sharing invisibility promise. */
final class InterviewOverlay {
    private static InterviewOverlay visible;
    static void hideForCapture(boolean hidden) { if (visible != null && visible.panel != null) visible.panel.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE); }
    private final Context context;
    private final InterviewRuntime runtime;
    private final Runnable stop;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private LinearLayout panel;
    private TextView status, answer;
    private ScrollView scroll;
    private Button captureButton;
    private WindowManager.LayoutParams params;
    private boolean expanded = true;
    private boolean screenshotSelected;
    private String actionError = "", displayedCard = "";
    private final BroadcastReceiver receiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { update(); } };
    InterviewOverlay(Context context, InterviewRuntime runtime, Runnable stop) { this.context = context; this.runtime = runtime; this.stop = stop; windows = context.getSystemService(WindowManager.class); }
    void show() {
        panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(32, 42, 66)); bg.setCornerRadius(dp(16)); panel.setBackground(bg); panel.setElevation(dp(8));
        Button title = button("面试提示 · 点击收起 / 拖动"); panel.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        status = new TextView(context); status.setTextColor(Color.rgb(175, 200, 255)); status.setTextSize(12); panel.addView(status);
        answer = new TextView(context); answer.setTextColor(Color.WHITE); answer.setTextSize(16); answer.setPadding(0, dp(8), 0, dp(8));
        scroll = new ScrollView(context); scroll.addView(answer); panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(180)));
        LinearLayout actions = new LinearLayout(context);
        Button hint = button("提示"), capture = button("截图"), close = button("停听");
        captureButton = capture;
        capture.setContentDescription("截图并发送给识屏模型回答");
        actions.addView(hint, new LinearLayout.LayoutParams(0, dp(44), 1)); actions.addView(capture, new LinearLayout.LayoutParams(0, dp(44), 1)); actions.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1)); panel.addView(actions);
        hint.setOnClickListener(v -> {
            screenshotSelected = false; actionError = "";
            runtime.command("hint", new JSONObject(), (value, error) -> main.post(() -> { if (error != null) actionError = error.getMessage(); update(); }));
        });
        close.setOnClickListener(v -> stop.run());
        capture.setOnClickListener(v -> {
            capture.setEnabled(false);
            screenshotSelected = true; actionError = "";
            runtime.command("screenshot", new JSONObject(), (value, error) -> main.post(() -> {
                if (error != null) actionError = error.getMessage();
                else if (panel != null) CaptureService.captureInterview(context, (String) value);
                else runtime.screenshotFailed((String) value, "面试悬浮窗已关闭，请重新截图");
                update();
            }));
        });
        params = new WindowManager.LayoutParams(dp(280), -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = dp(12); params.y = dp(100);
        title.setOnClickListener(v -> { expanded = !expanded; scroll.setVisibility(expanded ? View.VISIBLE : View.GONE); title.setText(expanded ? "面试提示 · 点击收起 / 拖动" : "面试提示 · 点击展开 / 拖动"); });
        title.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int x, y; boolean dragged;
            public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = e.getRawX(); downY = e.getRawY(); x = params.x; y = params.y; dragged = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                        params.x = Math.max(0, Math.min(metrics.widthPixels - dp(280), x + (int) dx));
                        params.y = Math.max(0, Math.min(metrics.heightPixels - panel.getHeight() - dp(60), y + (int) dy)); windows.updateViewLayout(panel, params);
                    }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!dragged) view.performClick(); return true; }
                return false;
            }
        });
        windows.addView(panel, params); visible = this;
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(InterviewRuntime.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED); update();
    }
    private Button button(String text) { Button value = new Button(context); value.setText(text); value.setTextSize(13); value.setTextColor(Color.WHITE); value.setBackgroundColor(Color.TRANSPARENT); value.setMinWidth(0); value.setPadding(0, 0, 0, 0); return value; }
    private void update() {
        runtime.command("snapshot", new JSONObject(), (value, error) -> main.post(() -> {
            if (panel == null || error != null) return;
            JSONObject state = (JSONObject) value;
            status.setText((state.optBoolean("diagnostic") ? "本地检测" : "连续识别") + " · 音量 " + Math.round(state.optDouble("level") * 100) + "%");
            String content = state.optString("error");
            if (content.isEmpty()) content = state.optString("warning");
            JSONArray cards = state.optJSONObject("session").optJSONArray("hints");
            boolean screenshotBusy = false;
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.optJSONObject(i);
                if (card.optString("source").equals("screenshot") && InterviewEngine.busy(card)) screenshotBusy = true;
            }
            captureButton.setEnabled(!screenshotBusy);
            captureButton.setAlpha(screenshotBusy ? 0.5f : 1f);
            for (int i = cards.length() - 1; i >= 0; i--) {
                JSONObject card = cards.optJSONObject(i);
                if (screenshotSelected != card.optString("source").equals("screenshot")) continue;
                if (state.optBoolean("diagnostic") && !screenshotSelected) break;
                String key = state.optJSONObject("session").optString("id") + ":" + card.optInt("id");
                if (!key.equals(displayedCard)) { displayedCard = key; scroll.scrollTo(0, 0); }
                String phase = card.optString("status");
                content = phase.equals("capturing") ? "正在截取屏幕，请完成系统共享授权…" : phase.equals("waiting") ? "对方还在说，等待完整问题…" : card.optString("text");
                if (!card.optString("error").isEmpty()) content += (content.isEmpty() ? "" : "\n") + card.optString("error");
                if (content.isEmpty()) content = phase.equals("stopped") ? "已停止" : phase.equals("done") ? "模型未返回文字，请检查所选视觉模型" : "正在组织回答…";
                if (screenshotSelected) status.setText("截图回答 · " + (InterviewEngine.busy(card) ? "处理中" : phase.equals("error") ? "失败，可重新截图" : "点提示切回语音"));
                break;
            }
            if (!screenshotSelected && state.optString("purpose").equals("screenshot") && !state.optString("transcript").isEmpty()) content = state.optString("transcript");
            if (!actionError.isEmpty()) content = actionError;
            if (!answer.getText().toString().equals(content)) answer.setText(content);
        }));
    }
    void close() { if (visible == this) visible = null; if (panel != null) { context.unregisterReceiver(receiver); windows.removeView(panel); panel = null; answer = null; } }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
