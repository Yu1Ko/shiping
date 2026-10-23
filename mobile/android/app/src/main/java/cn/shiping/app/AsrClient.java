package cn.shiping.app;

import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fun-ASR duplex protocol. Never queues audio before task-started or across reconnects. */
final class AsrClient extends WebSocketListener {
    interface Listener {
        void ready();
        void sentence(String text, boolean end);
        void ended(String error, boolean retryable);
    }
    private final OkHttpClient client;
    private final ScheduledExecutorService scheduler;
    private final Listener listener;
    private final String task = UUID.randomUUID().toString();
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean ready, finishing;
    private ScheduledFuture<?> deadline;
    private JSONObject config;
    private int sentenceId = -1;
    private boolean sentenceFinal;

    AsrClient(OkHttpClient client, ScheduledExecutorService scheduler, Listener listener) {
        this.client = client; this.scheduler = scheduler; this.listener = listener;
    }
    static void validate(JSONObject config) throws Exception {
        URI uri = new URI(config.optString("endpoint"));
        if (!"wss".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("连续 ASR 需要有效的 WSS 地址，不含账号、查询参数或片段");
        if (config.optString("apiKey").trim().isEmpty()) throw new IllegalArgumentException("请先在语音设置填写百炼 ASR API Key");
        if (config.optString("model").trim().isEmpty()) throw new IllegalArgumentException("请填写实时 ASR 模型名称");
    }
    void start(JSONObject configuration) throws Exception {
        validate(configuration); config = configuration;
        deadline = scheduler.schedule(() -> fail("ASR 连接或任务启动超时", true), 20, TimeUnit.SECONDS);
        socket = client.newWebSocket(new Request.Builder().url(config.getString("endpoint"))
            .header("Authorization", "Bearer " + config.getString("apiKey").trim()).build(), this);
    }
    static JSONObject command(String action, String task, JSONObject payload) {
        return InterviewEngine.json("header", InterviewEngine.json("action", action, "task_id", task, "streaming", "duplex"), "payload", payload);
    }
    @Override public void onOpen(WebSocket webSocket, Response response) {
        if (ended.get()) { webSocket.cancel(); return; }
        JSONObject parameters = InterviewEngine.json("format", "pcm", "sample_rate", 16000,
            "max_sentence_silence", Math.max(200, Math.min(6000, config.optInt("silenceMs", 800))), "heartbeat", true);
        String vocabulary = config.optString("vocabularyId").trim();
        if (!vocabulary.isEmpty()) InterviewEngine.put(parameters, "vocabulary_id", vocabulary);
        String language = config.optString("language");
        if (!language.isEmpty()) InterviewEngine.put(parameters, "language_hints", new org.json.JSONArray().put(language));
        JSONObject payload = InterviewEngine.json("task_group", "audio", "task", "asr", "function", "recognition",
            "model", config.optString("model"), "parameters", parameters, "input", new JSONObject());
        if (!webSocket.send(command("run-task", task, payload).toString())) fail("ASR 启动消息发送失败", true);
    }
    @Override public void onMessage(WebSocket webSocket, String raw) {
        if (ended.get()) return;
        try {
            JSONObject message = new JSONObject(raw), header = message.getJSONObject("header");
            if (!task.equals(header.optString("task_id"))) return;
            switch (header.optString("event")) {
                case "task-started":
                    if (finishing) { webSocket.send(command("finish-task", task, InterviewEngine.json("input", new JSONObject())).toString()); return; }
                    if (deadline != null) deadline.cancel(false);
                    ready = true; listener.ready(); break;
                case "result-generated":
                    JSONObject sentence = message.optJSONObject("payload").optJSONObject("output").optJSONObject("sentence");
                    if (sentence == null || sentence.optBoolean("heartbeat")) return;
                    int incoming = sentence.optInt("sentence_id", -1);
                    if (incoming >= 0 && (incoming < sentenceId || incoming == sentenceId && sentenceFinal)) return;
                    if (incoming != sentenceId) sentenceFinal = false;
                    sentenceId = incoming; sentenceFinal = sentence.optBoolean("sentence_end");
                    listener.sentence(sentence.optString("text"), sentenceFinal); break;
                case "task-finished": complete(null, false); webSocket.close(1000, "finished"); break;
                case "task-failed":
                    String code = header.optString("error_code");
                    String detail = header.optString("error_message", "语音识别失败").replace(config.optString("apiKey"), "[已隐藏]");
                    fail("ASR " + code + "：" + detail.substring(0, Math.min(detail.length(), 300)), false); break;
                default: break;
            }
        } catch (Exception e) { fail("ASR 返回的数据格式不完整，请检查实时服务地址与模型", false); }
    }
    boolean audio(byte[] pcm, int count) {
        WebSocket current = socket;
        if (!ready || finishing || ended.get() || current == null) return false;
        if (current.queueSize() > 96000) { fail("ASR 上传积压超过 3 秒，网络过慢；本段音频未缓存", true); return false; }
        if (!current.send(ByteString.of(pcm, 0, count))) { fail("ASR 音频连接已关闭", true); return false; }
        return true;
    }
    boolean finish() {
        finishing = true;
        if (ended.get()) return false;
        if (deadline != null) deadline.cancel(false);
        if (ready && socket != null) socket.send(command("finish-task", task, InterviewEngine.json("input", new JSONObject())).toString());
        else { cancel(); return false; }
        deadline = scheduler.schedule(() -> { complete("末句确认超时，已保留当前转录", false); if (socket != null) socket.cancel(); }, 4, TimeUnit.SECONDS);
        return true;
    }
    void cancel() { complete(null, false); if (socket != null) socket.cancel(); }
    private void fail(String error, boolean retryable) { complete(error, retryable); if (socket != null) socket.cancel(); }
    private void complete(String error, boolean retryable) {
        if (!ended.compareAndSet(false, true)) return;
        ready = false;
        if (deadline != null) deadline.cancel(false);
        listener.ended(error, retryable && !finishing);
    }
    @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
        int code = response == null ? 0 : response.code();
        fail(code == 401 || code == 403 ? "ASR 鉴权失败，请检查百炼密钥、地域和业务空间" : "ASR 网络连接中断" + (code > 0 ? "（HTTP " + code + "）" : ""), code != 401 && code != 403);
    }
    @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, null); }
    @Override public void onClosed(WebSocket socket, int code, String reason) { complete(finishing ? null : "ASR 连接已结束（" + code + "）", !finishing); }
}
