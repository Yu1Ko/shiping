package cn.shiping.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class AiClient {
    private final OkHttpClient client;
    AiClient() {
        this(new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build());
    }
    AiClient(OkHttpClient client) { this.client = client; }
    private final ConcurrentHashMap<String, Call> calls = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> thinkingRefused = ConcurrentHashMap.newKeySet();

    void cancel(String id) {
        cancelled.add(id);
        Call call = calls.get(id);
        if (call != null) call.cancel();
    }

    void close() {
        for (String id : calls.keySet()) cancel(id);
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    void chat(String id, JSONObject profile, JSONObject params, ChatProtocol.Delta delta) throws Exception {
        try {
            JSONObject base = new JSONObject().put("model", profile.getString("model"))
                .put("stream", true).put("messages", params.getJSONArray("messages"));
            String fingerprint = profile.optString("baseURL") + "|" + profile.optString("model");
            boolean thinking = profile.optBoolean("disableThinking") && !thinkingRefused.contains(fingerprint);
            for (int attempt = 0; attempt < 2; attempt++) {
                if (cancelled.contains(id)) throw new InterruptedException("已停止生成");
                JSONObject body = new JSONObject(base.toString());
                if (thinking) ChatProtocol.thinking(body, profile);
                Request.Builder request = new Request.Builder()
                    .url(ChatProtocol.endpoint(profile.getString("baseURL"), "chat/completions"))
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")));
                ChatProtocol.headers(request, profile);
                Call call = client.newCall(request.build());
                calls.put(id, call);
                if (cancelled.contains(id)) call.cancel();
                try (Response response = call.execute()) {
                    if (response.body() == null) throw new IllegalStateException("AI 服务返回了空响应");
                    if (!response.isSuccessful()) {
                        String error = response.peekBody(16384).string();
                        if (thinking && attempt == 0 && ChatProtocol.thinkingRejected(response.code(), error)) {
                            thinkingRefused.add(fingerprint);
                            thinking = false;
                            delta.emit("notice", "此模型不支持关闭思考，已自动兼容重试。");
                            continue;
                        }
                        throw new IllegalStateException(httpError(response.code(), error, profile));
                    }
                    String type = response.header("Content-Type", "");
                    if (type.contains("application/json")) {
                        String value = response.peekBody(2 * 1024 * 1024).string();
                        if (!ChatProtocol.frame(value, delta)) throw new IllegalStateException("AI 返回的 JSON 没有完整回答");
                    } else {
                        ChatProtocol.readStream(new BufferedReader(response.body().charStream()), (kind, text) -> {
                            if (cancelled.contains(id)) throw new InterruptedException("已停止生成");
                            delta.emit(kind, text);
                        });
                    }
                    return;
                }
            }
        } finally {
            calls.remove(id);
            cancelled.remove(id);
        }
    }

    JSONArray models(JSONObject profile) throws Exception {
        Request.Builder builder = new Request.Builder().url(ChatProtocol.endpoint(profile.getString("baseURL"), "models"));
        ChatProtocol.headers(builder, profile);
        try (Response response = client.newCall(builder.build()).execute()) {
            String text = response.peekBody(2 * 1024 * 1024).string();
            if (!response.isSuccessful()) throw new IllegalStateException(httpError(response.code(), text, profile));
            JSONArray data = new JSONObject(text).getJSONArray("data");
            JSONArray ids = new JSONArray();
            for (int i = 0; i < Math.min(data.length(), 2000); i++) ids.put(data.getJSONObject(i).getString("id"));
            return ids;
        }
    }

    private String httpError(int status, String raw, JSONObject profile) {
        String hint = switch (status) {
            case 401, 403 -> "请检查 API Key 和模型权限";
            case 404 -> "请检查 Base URL 与模型名称";
            case 429 -> "额度不足或请求过于频繁，请稍后重试";
            default -> "请检查平台状态和请求配置";
        };
        String detail = "";
        try {
            JSONObject error = new JSONObject(raw).optJSONObject("error");
            if (error != null) detail = error.optString("message");
        } catch (Exception ignored) { /* HTML proxy errors are intentionally not rendered. */ }
        String key = profile.optString("apiKey");
        if (!key.isEmpty()) detail = detail.replace(key, "[已隐藏]");
        return "HTTP " + status + " · " + hint + (detail.isEmpty() ? "" : "\n" + detail.substring(0, Math.min(600, detail.length())));
    }
}
