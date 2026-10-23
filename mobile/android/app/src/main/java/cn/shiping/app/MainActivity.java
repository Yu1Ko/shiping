package cn.shiping.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JsResult;
import android.widget.FrameLayout;
import androidx.webkit.WebViewAssetLoader;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 101, PROJECTION = 102, OVERLAY = 103, SPEECH = 104, DOCUMENT = 105;
    private WebView web;
    private SecureStore store;
    private final AiClient ai = new AiClient();
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final ExecutorService networkControl = Executors.newSingleThreadExecutor();
    private final List<JSONObject> queued = new ArrayList<>();
    private boolean ready = false;
    private String captureCall;
    private boolean wantOverlay;
    private String listeningCall, exportText;
    private String interviewCaptureRequest;
    private JSONObject listeningArgs;
    private final BroadcastReceiver interviewReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { sendInterviewSnapshot(); }
    };
    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            emit(json("event", "captureStatus", "active", intent.getBooleanExtra("active", false)));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) interviewCaptureRequest = state.getString("interviewCaptureRequest");
        store = new SecureStore(this);
        IntentFilter captureFilter = new IntentFilter("cn.shiping.CAPTURE_STATUS");
        ContextCompat.registerReceiver(this, captureReceiver, captureFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, interviewReceiver, new IntentFilter(InterviewRuntime.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else root.setFitsSystemWindows(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        web.setBackgroundColor(Color.rgb(246, 248, 252));
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = loader.shouldInterceptRequest(request.getUrl());
                return response != null ? response : new WebResourceResponse("text/plain", "UTF-8", 403,
                    "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame() && "https".equals(request.getUrl().getScheme()) && !"appassets.androidplatform.net".equals(request.getUrl().getHost())) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("打开外部链接")
                        .setMessage(request.getUrl().toString()).setNegativeButton("取消", null)
                        .setPositiveButton("打开", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); }
                            catch (Exception e) { notice("没有可打开此链接的浏览器"); }
                        }).show();
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "ShipingNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("确认", (dialog, which) -> result.confirm())
                    .setNegativeButton("取消", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/public/index.html");
        acceptIntent(getIntent());
    }

    private final class Bridge {
        @JavascriptInterface public void postMessage(String raw) {
            if (raw.length() > 34 * 1024 * 1024) return;
            try {
                JSONObject message = new JSONObject(raw);
                String id = message.getString("id");
                String method = message.getString("method");
                JSONObject params = message.optJSONObject("params");
                JSONObject args = params == null ? new JSONObject() : params;
                if (method.equals("load") || method.equals("save")) {
                    storage.execute(() -> {
                        try {
                            if (method.equals("save")) {
                                store.write(args.getJSONObject("state").toString());
                                resolve(id, true);
                            } else {
                                String value = store.read();
                                resolve(id, new JSONObject().put("state", value.equals("null") ? JSONObject.NULL : new JSONObject(value))
                                    .put("version", "1.1.1").put("android", Build.VERSION.RELEASE));
                            }
                        } catch (Exception e) { reject(id, e); }
                    });
                } else if (method.equals("interview")) {
                    InterviewRuntime.get(MainActivity.this).command(args.getString("action"), args, (value, error) -> { if (error != null) reject(id, error); else resolve(id, value); });
                } else if (method.equals("startListening")) {
                    storage.execute(() -> {
                        try {
                            if (!args.optBoolean("diagnostic")) AsrClient.validate(new JSONObject(store.read()).getJSONObject("settings").getJSONObject("asr"));
                            runOnUiThread(() -> { try { uiCall(id, method, args); } catch (Exception e) { reject(id, e); } });
                        } catch (Exception e) { reject(id, e); }
                    });
                } else if (method.equals("chat") || method.equals("models")) {
                    network.execute(() -> {
                        try {
                            JSONObject profile = findProfile(args.getString("profileId"));
                            if (method.equals("models")) { resolve(id, ai.models(profile)); return; }
                            ai.chat(id, profile, args, (kind, text) -> emit(json("event", "delta", "requestId", id, "kind", kind, "text", text)));
                            resolve(id, true);
                        } catch (Exception e) { reject(id, e); }
                    });
                } else runOnUiThread(() -> {
                    try { uiCall(id, method, args); } catch (Exception e) { reject(id, e); }
                });
            } catch (Exception e) { notice("无法处理操作，请重新打开应用"); }
        }
    }

    private JSONObject findProfile(String id) throws Exception {
        JSONObject state = new JSONObject(store.read());
        JSONArray profiles = state.getJSONObject("settings").getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            if (profile.getString("id").equals(id)) return profile;
        }
        throw new IllegalArgumentException("AI 配置不存在，请先保存设置");
    }

    private void uiCall(String id, String method, JSONObject args) throws Exception {
        switch (method) {
            case "ready":
                ready = true;
                for (JSONObject event : new ArrayList<>(queued)) deliver(event);
                queued.clear();
                resolve(id, true);
                break;
            case "cancel":
                String requestId = args.getString("requestId");
                networkControl.execute(() -> { ai.cancel(requestId); resolve(id, true); });
                break;
            case "pickImages":
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(pick, PICK_IMAGE);
                resolve(id, true);
                break;
            case "pickDocument":
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}).addCategory(Intent.CATEGORY_OPENABLE), DOCUMENT);
                resolve(id, true);
                break;
            case "capture":
                if (captureCall != null) throw new IllegalStateException("截屏授权正在进行");
                if (CaptureService.active) throw new IllegalStateException("截屏已开启，请切到目标应用后点悬浮按钮；也可先停止截屏");
                captureCall = id;
                wantOverlay = args.optBoolean("overlay");
                if (wantOverlay && !Settings.canDrawOverlays(this)) {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY);
                } else requestProjection();
                break;
            case "stopCapture": stopService(new Intent(this, CaptureService.class)); resolve(id, true); break;
            case "captureStatus": resolve(id, CaptureService.active); break;
            case "startListening":
                if (ListeningService.active || listeningCall != null) throw new IllegalStateException("监听或授权已在进行，请先停止");
                if ("meeting".equals(args.optString("source")) && Build.VERSION.SDK_INT < 33) throw new IllegalStateException("腾讯会议调试采集需要 Android 13 或更新版本");
                if (!"microphone".equals(args.optString("source")) && Build.VERSION.SDK_INT < 29) throw new IllegalStateException("应用音频捕获需要 Android 10 或更新版本");
                listeningCall = id; listeningArgs = args;
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 107);
                else requestListening();
                break;
            case "stopListening":
                if (ListeningService.active) startService(new Intent(this, ListeningService.class).setAction("stop"));
                resolve(id, true); break;
            case "overlaySettings":
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); resolve(id, true); break;
            case "saveText":
                if (exportText != null) throw new IllegalStateException("已有导出正在进行");
                exportText = args.getString("text");
                try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "面试记录.md"), 110); }
                catch (Exception e) { exportText = null; throw e; }
                resolve(id, true); break;
            case "minimize": moveTaskToBack(true); resolve(id, true); break;
            case "speech":
                Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出问题，识别完成后可编辑再发送");
                if (speech.resolveActivity(getPackageManager()) == null) throw new IllegalStateException("此手机没有系统语音识别服务，可使用输入法的语音输入");
                startActivityForResult(speech, SPEECH);
                resolve(id, true);
                break;
            case "copy":
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("识屏", args.getString("text")));
                resolve(id, true);
                break;
            case "share":
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.getString("text")), "导出对话"));
                resolve(id, true);
                break;
            default: throw new IllegalArgumentException("不支持的操作");
        }
    }

    private void requestProjection() {
        try {
            if (ProjectionSession.available()) {
                startForegroundService(new Intent(this, CaptureService.class).putExtra("overlay", wantOverlay));
                resolve(captureCall, true); captureCall = null; moveTaskToBack(true); return;
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 106);
            }
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION);
        } catch (Exception e) { reject(captureCall, e); captureCall = null; }
    }

    private void requestListening() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 111);
            return;
        }
        requestListeningProjection();
    }

    private void requestListeningProjection() {
        try {
            if ("microphone".equals(listeningArgs.optString("source")) || "meeting".equals(listeningArgs.optString("source")) || ProjectionSession.available()) launchListening(-1, null);
            else startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 108);
        } catch (Exception e) { reject(listeningCall, e); listeningCall = null; listeningArgs = null; }
    }

    private void launchListening(int result, Intent data) {
        try {
            startForegroundService(new Intent(this, ListeningService.class).putExtra("source", listeningArgs.optString("source", "playback"))
                .putExtra("purpose", listeningArgs.optString("purpose", "interview")).putExtra("diagnostic", listeningArgs.optBoolean("diagnostic"))
                .putExtra("result", result).putExtra("data", data));
            resolve(listeningCall, true);
        } catch (Exception e) { reject(listeningCall, e); }
        listeningCall = null; listeningArgs = null;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 111 && listeningCall != null) requestListeningProjection();
        if (request == 107 && listeningCall != null) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) requestListening();
            else { reject(listeningCall, new IllegalStateException("未授权录音，连续识别未启动")); listeningCall = null; listeningArgs = null; }
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 112) {
            String capture = interviewCaptureRequest; interviewCaptureRequest = null;
            if (capture != null) {
                if (result == RESULT_OK && data != null && ListeningService.active) {
                    try { startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.INTERVIEW_CAPTURE).putExtra("request", capture).putExtra("result", result).putExtra("data", data).putExtra("returning", true)); }
                    catch (Exception e) { InterviewRuntime.get(this).screenshotFailed(capture, "无法开启截图：" + e.getMessage()); }
                } else InterviewRuntime.get(this).screenshotFailed(capture, "已取消截屏或监听已结束；连续识别不受取消授权影响");
                moveTaskToBack(true);
            }
        } else if (request == 108) {
            if (result == RESULT_OK && data != null) launchListening(result, data);
            else { reject(listeningCall, new IllegalStateException("已取消应用音频授权")); listeningCall = null; listeningArgs = null; }
        } else if (request == 110) {
            String text = exportText; exportText = null;
            if (result == RESULT_OK && data != null && data.getData() != null && text != null) {
                Uri destination = data.getData();
                storage.execute(() -> {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new IllegalStateException("无法写入文件");
                        output.write(text.getBytes(StandardCharsets.UTF_8)); notice("面试记录已导出");
                    } catch (Exception e) { notice("导出失败：" + e.getMessage()); }
                });
            }
        } else if (request == OVERLAY) {
            if (Settings.canDrawOverlays(this)) requestProjection();
            else { reject(captureCall, new IllegalStateException("尚未允许悬浮窗，可改用「单次截屏」或相册分享")); captureCall = null; }
        } else if (request == PROJECTION) {
            if (result == RESULT_OK && data != null) {
                try {
                    Intent service = new Intent(this, CaptureService.class).putExtra("result", result).putExtra("data", data).putExtra("overlay", wantOverlay);
                    startForegroundService(service);
                    resolve(captureCall, true);
                    moveTaskToBack(true);
                } catch (Exception e) { reject(captureCall, e); }
            } else reject(captureCall, new IllegalStateException("已取消截屏授权"));
            captureCall = null;
        } else if (result == RESULT_OK && data != null) {
            if (request == PICK_IMAGE) importImages(uris(data));
            else if (request == SPEECH) {
                ArrayList<String> lines = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (lines != null && !lines.isEmpty()) emit(json("event", "text", "text", lines.get(0)));
            } else if (request == DOCUMENT && data.getData() != null) {
                Uri uri = data.getData();
                storage.execute(() -> {
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IllegalArgumentException("无法读取资料");
                        String content = DocumentImport.read(this, input);
                        emit(json("event", "document", "text", content));
                    } catch (Exception e) { notice(e.getMessage()); }
                });
            }
        }
    }

    private List<Uri> uris(Intent data) {
        List<Uri> values = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < Math.min(4, data.getClipData().getItemCount()); i++) values.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) values.add(data.getData());
        return values;
    }

    private void importImages(List<Uri> uris) {
        storage.execute(() -> {
            JSONArray images = new JSONArray();
            for (Uri uri : uris) {
                try { images.put(ImageImport.fromUri(this, uri)); }
                catch (Exception e) { notice("图片读取失败：" + e.getMessage()); }
            }
            if (images.length() > 0) emit(json("event", "images", "images", images));
        });
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent); }

    private void acceptIntent(Intent intent) {
        consumeCapture();
        String action = intent.getAction();
        if (CaptureService.INTERVIEW_CAPTURE.equals(action)) {
            String capture = intent.getStringExtra("request"); intent.setAction(null);
            if (capture != null && interviewCaptureRequest == null) {
                interviewCaptureRequest = capture;
                try { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 112); }
                catch (Exception e) { InterviewRuntime.get(this).screenshotFailed(capture, "无法申请截屏：" + e.getMessage()); interviewCaptureRequest = null; }
            }
        }
        if ("cn.shiping.INTERVIEW".equals(action)) emit(json("event", "openInterview"));
        if (Intent.ACTION_SEND.equals(action)) {
            if (intent.getType() != null && intent.getType().startsWith("image/")) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) importImages(java.util.Collections.singletonList(uri));
            } else if ("text/plain".equals(intent.getType())) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) emit(json("event", "text", "text", text.substring(0, Math.min(text.length(), 10000))));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (values != null) importImages(values.subList(0, Math.min(4, values.size())));
        }
    }

    private void consumeCapture() {
        storage.execute(() -> {
            File file = new File(getFilesDir(), "capture.jpg");
            if (!file.exists()) return;
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] bytes = BoundedInput.read(stream, 4 * 1024 * 1024);
                String image = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                emit(json("event", "images", "images", new JSONArray().put(image)));
                if (!file.delete()) notice("临时截图未能清理，重新打开时可能再次显示");
                getSystemService(android.app.NotificationManager.class).cancel(18);
            } catch (Exception e) { notice("截屏读取失败，请重试"); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) {
            consumeCapture();
            sendInterviewSnapshot();
            emit(json("event", "captureStatus", "active", CaptureService.active));
            if (!CaptureService.lastError.isEmpty()) {
                notice(CaptureService.lastError);
                CaptureService.lastError = "";
            }
        }
    }

    private void sendInterviewSnapshot() {
        InterviewRuntime.get(this).command("snapshot", new JSONObject(), (value, error) -> {
            if (error == null) emit(json("event", "interview", "state", value));
        });
    }

    static JSONObject json(Object... values) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return object;
    }

    private void resolve(String id, Object value) { emit(json("id", id, "result", value)); }
    private void reject(String id, Exception error) { emit(json("id", id, "error", error.getMessage() == null ? "操作失败，请重试" : error.getMessage())); }
    private void notice(String message) { emit(json("event", "notice", "text", message == null ? "操作失败" : message)); }
    private void emit(JSONObject data) { runOnUiThread(() -> { if (ready || data.has("id")) deliver(data); else queued.add(data); }); }
    private void deliver(JSONObject data) {
        if (web != null) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('shiping-native',{detail:" + data + "}))", null);
    }

    @Override public void onBackPressed() { emit(json("event", "back")); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("interviewCaptureRequest", interviewCaptureRequest); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        unregisterReceiver(captureReceiver);
        unregisterReceiver(interviewReceiver);
        // TLS socket shutdown can write a close alert, so it must stay off the UI thread.
        networkControl.execute(ai::close);
        networkControl.shutdown();
        network.shutdownNow();
        storage.shutdown();
        if (web != null) { web.removeJavascriptInterface("ShipingNative"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
