package cn.shiping.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Locale;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Pure protocol rules, shared by streaming transport and JVM tests. */
final class ChatProtocol {
    interface Delta { void emit(String kind, String text) throws Exception; }

    static HttpUrl endpoint(String base, String route) {
        String raw = base.trim().replaceAll("/+$", "");
        if (raw.endsWith("/chat/completions")) raw = raw.substring(0, raw.length() - 17);
        HttpUrl url = HttpUrl.parse(raw + "/" + route);
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
            || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("API 地址须为 HTTPS，不能包含账号、查询参数或片段；例如 https://api.openai.com/v1");
        }
        return url;
    }

    static void headers(Request.Builder request, JSONObject profile) {
        String key = profile.optString("apiKey").trim();
        if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
        for (String line : profile.optString("headers").split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("自定义请求头格式应为 名称: 值");
            String name = line.substring(0, colon).trim();
            if (name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length")) {
                throw new IllegalArgumentException("不能覆盖 Host 或 Content-Length");
            }
            request.header(name, line.substring(colon + 1).trim());
        }
        request.header("Content-Type", "application/json");
    }

    static void thinking(JSONObject body, JSONObject profile) throws Exception {
        String host = endpoint(profile.getString("baseURL"), "chat/completions").host();
        if (host.equals("openrouter.ai") || host.endsWith(".openrouter.ai")) {
            body.put("reasoning", new JSONObject().put("enabled", false));
        } else if (host.equals("api.openai.com") || profile.getString("model").matches("(?i)^(gpt-|o\\d).*")) {
            body.put("reasoning_effort", "none");
        } else {
            body.put("thinking", new JSONObject().put("type", "disabled"));
            body.put("enable_thinking", false);
        }
    }

    static boolean thinkingRejected(int status, String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return (status == 400 || status == 422) && (lower.contains("thinking") || lower.contains("reasoning"));
    }

    static boolean frame(String raw, Delta delta) throws Exception {
        if (raw.trim().equals("[DONE]")) return true;
        JSONObject frame = new JSONObject(raw);
        if (frame.has("error")) throw new IOException("AI 服务返回错误：" + frame.get("error"));
        JSONArray choices = frame.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return false;
        JSONObject choice = choices.getJSONObject(0);
        JSONObject part = choice.optJSONObject("delta");
        if (part == null) part = choice.optJSONObject("message");
        if (part != null) {
            Object content = part.opt("content");
            if (content instanceof String && !((String) content).isEmpty()) delta.emit("text", (String) content);
            String reasoning = part.optString("reasoning_content", "");
            if (reasoning.isEmpty()) reasoning = part.optString("reasoning", "");
            if (!reasoning.isEmpty()) delta.emit("reasoning", reasoning);
        }
        String reason = choice.optString("finish_reason", "");
        if (reason.equals("length")) delta.emit("notice", "回答达到模型长度上限，可继续追问。");
        if (reason.equals("content_filter")) throw new IOException("回答被服务商内容过滤器中止");
        return !reason.isEmpty() && !reason.equals("null");
    }

    static void readStream(BufferedReader reader, Delta delta) throws Exception {
        StringBuilder data = new StringBuilder();
        String line;
        boolean finished = false;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    if (frame(data.toString(), delta)) finished = true;
                    data.setLength(0);
                    if (finished) return;
                }
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
                if (data.length() > 2 * 1024 * 1024) throw new IOException("AI 响应帧过大");
            }
        }
        if (data.length() > 0) finished = frame(data.toString(), delta);
        if (!finished) throw new IOException("连接提前结束，回答可能不完整，请重试或继续追问");
    }
}
