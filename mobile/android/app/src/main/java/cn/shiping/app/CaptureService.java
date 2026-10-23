package cn.shiping.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/** A single consent creates a single virtual display, including Android 14+. */
public class CaptureService extends Service {
    static final String INTERVIEW_CAPTURE = "cn.shiping.INTERVIEW_CAPTURE";
    private static CaptureService instance;
    static void captureInterview(android.content.Context context, String request) {
        try {
            if (ProjectionSession.available()) context.startForegroundService(new Intent(context, CaptureService.class).setAction(INTERVIEW_CAPTURE).putExtra("request", request));
            else context.startActivity(new Intent(context, MainActivity.class).setAction(INTERVIEW_CAPTURE).putExtra("request", request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception e) { InterviewRuntime.get(context).screenshotFailed(request, "无法启动截图：" + e.getMessage()); }
    }
    static void stopInterviewSession() { if (instance != null && instance.interviewSession) instance.stopSelf(); }
    static volatile boolean active = false;
    static volatile String lastError = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private ImageReader reader;
    private Image latestFrame;
    private WindowManager windows;
    private LinearLayout toolbar;
    private WindowManager.LayoutParams toolbarParams;
    private int width, height, density;
    private boolean awaitingFrame, capturing, overlay, stopping;
    private boolean interviewSession;
    private String interviewRequest;
    private final Runnable captureTimeout = () -> captureFailed("没有收到画面，请检查共享范围并重新授权");

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        boolean interview = INTERVIEW_CAPTURE.equals(intent.getAction());
        if (interview && !ListeningService.active) {
            InterviewRuntime.get(this).screenshotFailed(intent.getStringExtra("request"), "监听已结束，请重新开始后截图");
            if (!active || interviewSession) stopSelf();
            return START_NOT_STICKY;
        }
        if (active) {
            if (interview) {
                if (capturing || interviewRequest != null) InterviewRuntime.get(this).screenshotFailed(intent.getStringExtra("request"), "已有截图正在进行，请稍后重试");
                else { interviewRequest = intent.getStringExtra("request"); requestCapture(); }
            }
            return START_NOT_STICKY;
        }
        interviewSession = interview;
        interviewRequest = interview ? intent.getStringExtra("request") : null;
        instance = this;
        try {
            lastError = "";
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("capture", "截屏会话", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, CaptureService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "capture")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("识屏 · 截屏已开启")
                .setContentText("点悬浮按钮截取画面；可随时停止共享")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止截屏", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(17, notification);
            overlay = intent.getBooleanExtra("overlay", false) && Settings.canDrawOverlays(this);
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent data = intent.getParcelableExtra("data");
            projection = ProjectionSession.acquire(this, intent.getIntExtra("result", -1), data);
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
                @Override public void onCapturedContentResize(int newWidth, int newHeight) {
                    if (!stopping && display != null) resize(newWidth, newHeight);
                }
            };
            projection.registerCallback(projectionCallback, handler);
            windows = getSystemService(WindowManager.class);
            DisplayMetrics metrics = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            density = metrics.densityDpi;
            setSize(metrics.widthPixels, metrics.heightPixels);
            reader = makeReader();
            display = ProjectionSession.attachDisplay(projection, width, height, density, reader.getSurface());
            active = true;
            sendBroadcast(new Intent("cn.shiping.CAPTURE_STATUS").setPackage(getPackageName()).putExtra("active", true));
            if (interview) handler.postDelayed(this::requestCapture, intent.getBooleanExtra("returning", false) ? 1200 : 0);
            else if (overlay) showToolbar();
            else {
                Toast.makeText(this, "请切到目标画面，5 秒后截屏", Toast.LENGTH_LONG).show();
                handler.postDelayed(this::requestCapture, 5000);
            }
        } catch (Exception e) {
            lastError = "无法开启截屏：" + e.getMessage();
            InterviewRuntime.get(this).screenshotFailed(interviewRequest, lastError); interviewRequest = null;
            android.util.Log.w("ShipingCapture", "Capture startup failed", e);
            Toast.makeText(this, "无法开启截屏：" + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void setSize(int rawWidth, int rawHeight) {
        double scale = Math.min(1, 1600.0 / Math.max(rawWidth, rawHeight));
        width = Math.max(1, (int) (rawWidth * scale));
        height = Math.max(1, (int) (rawHeight * scale));
    }

    private void resize(int rawWidth, int rawHeight) {
        if (rawWidth <= 0 || rawHeight <= 0) return;
        int previousWidth = width, previousHeight = height;
        setSize(rawWidth, rawHeight);
        if (width == previousWidth && height == previousHeight) return;
        try {
            display.setSurface(null);
            if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
            reader.close();
            reader = makeReader();
            display.resize(width, height, density);
            display.setSurface(reader.getSurface());
        } catch (Exception e) {
            Toast.makeText(this, "屏幕尺寸变化，请重新开启截屏", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private ImageReader makeReader() {
        ImageReader value = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        value.setOnImageAvailableListener(source -> {
            try {
                if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
                latestFrame = source.acquireLatestImage();
                if (awaitingFrame) captureFrame();
            } catch (Exception e) { captureTimeout.run(); }
        }, handler);
        return value;
    }

    private void captureFrame() {
            Image frame = latestFrame;
            latestFrame = null;
            try (Image image = frame) {
                if (image == null) return;
                awaitingFrame = false;
                handler.removeCallbacks(captureTimeout);
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(buffer);
                Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
                boolean interview = interviewRequest != null;
                try {
                    if (interview) {
                        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                        cropped.compress(Bitmap.CompressFormat.JPEG, 86, output);
                        String data = "data:image/jpeg;base64," + android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
                        InterviewRuntime.get(this).screenshotReady(interviewRequest, data);
                        interviewRequest = null;
                    } else try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "capture.jpg"))) {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 86, output);
                    }
                } finally {
                    if (cropped != padded) cropped.recycle();
                    padded.recycle();
                }
                if (interview) { finishCapture(); return; }
                Intent open = new Intent(this, MainActivity.class).setAction("cn.shiping.CAPTURED")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent result = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                getSystemService(NotificationManager.class).notify(18, new Notification.Builder(this, "capture")
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("截图已就绪")
                    .setContentText("点此返回识屏，确认图片后发送").setAutoCancel(true).setContentIntent(result).build());
                startActivity(open);
                handler.postDelayed(() -> {
                    finishCapture();
                }, 500);
            } catch (Exception e) {
                lastError = "截屏失败：" + e.getMessage();
                android.util.Log.w("ShipingCapture", "Capture frame failed", e);
                captureFailed(lastError);
            }
    }

    private void requestCapture() {
        if (capturing || stopping) return;
        capturing = true;
        // Keep the overlay out of the captured frame, then acquire a fresh frame.
        if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
        InterviewOverlay.hideForCapture(true);
        if (toolbar != null) toolbar.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (stopping) return;
            awaitingFrame = true;
            handler.postDelayed(captureTimeout, 8000);
            if (latestFrame != null) captureFrame();
        }, 350);
    }

    private void captureFailed(String message) {
        if (interviewRequest != null) InterviewRuntime.get(this).screenshotFailed(interviewRequest, message);
        else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        interviewRequest = null;
        finishCapture();
    }
    private void finishCapture() {
        awaitingFrame = false; capturing = false;
        handler.removeCallbacks(captureTimeout);
        InterviewOverlay.hideForCapture(false);
        if (toolbar != null) toolbar.setVisibility(View.VISIBLE);
        if (!overlay && !interviewSession) stopSelf();
    }

    private void showToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(42, 63, 157));
        background.setCornerRadius(dp(28));
        toolbar.setBackground(background);
        toolbar.setElevation(dp(8));
        Button capture = new Button(this);
        capture.setText("识屏 · 截图");
        capture.setContentDescription("截取当前屏幕，可拖动");
        capture.setTextColor(Color.WHITE);
        capture.setTextSize(14);
        capture.setBackgroundColor(Color.TRANSPARENT);
        capture.setMinWidth(0);
        toolbar.addView(capture, new LinearLayout.LayoutParams(dp(116), dp(48)));
        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(22);
        close.setTextColor(Color.WHITE);
        close.setContentDescription("停止截屏并关闭悬浮窗");
        close.setMinWidth(0);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(48)));
        close.setOnClickListener(view -> stopSelf());
        toolbarParams = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        toolbarParams.gravity = Gravity.TOP | Gravity.START;
        toolbarParams.x = dp(12);
        toolbarParams.y = dp(140);
        capture.setOnClickListener(view -> requestCapture());
        capture.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int x, y;
            boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); x = toolbarParams.x; y = toolbarParams.y; dragged = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        DisplayMetrics screen = getResources().getDisplayMetrics();
                        toolbarParams.x = Math.max(0, Math.min(screen.widthPixels - dp(160), x + (int) dx));
                        toolbarParams.y = Math.max(0, Math.min(screen.heightPixels - dp(80), y + (int) dy));
                        windows.updateViewLayout(toolbar, toolbarParams);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) { if (!dragged) view.performClick(); return true; }
                return false;
            }
        });
        windows.addView(toolbar, toolbarParams);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onDestroy() {
        stopping = true;
        active = false;
        if (instance == this) instance = null;
        InterviewRuntime.get(this).screenshotFailed(interviewRequest, "截屏共享已停止，请重新截图授权"); interviewRequest = null;
        InterviewOverlay.hideForCapture(false);
        sendBroadcast(new Intent("cn.shiping.CAPTURE_STATUS").setPackage(getPackageName()).putExtra("active", false));
        handler.removeCallbacksAndMessages(null);
        if (toolbar != null) { windows.removeView(toolbar); toolbar = null; }
        if (display != null) { ProjectionSession.detachDisplay(display); display = null; }
        if (latestFrame != null) { latestFrame.close(); latestFrame = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.unregisterCallback(projectionCallback); ProjectionSession.release(projection); projection = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
