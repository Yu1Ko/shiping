package cn.shiping.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Serialized by its owner. Independent of Android so conversation rules can be tested. */
final class InterviewEngine {
    interface Output {
        void generate(String request, JSONArray messages);
        void cancel(String request);
        void changed();
    }
    private final Output output;
    private final List<JSONObject> utterances = new ArrayList<>(), hints = new ArrayList<>();
    private String id = UUID.randomUUID().toString();
    private long created = System.currentTimeMillis();
    private int nextUtterance = 1, nextHint = 1, hintedUpTo;
    boolean automatic = true;
    int minChars = 4;

    InterviewEngine(Output output) { this.output = output; }
    static JSONObject json(Object... values) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return object;
    }
    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    static int meaningful(String text) { return text.replaceAll("[\\s\\p{P}\\p{S}]", "").length(); }
    JSONObject snapshot() {
        return json("id", id, "created", created, "utterances", new JSONArray(utterances), "hints", new JSONArray(hints));
    }
    void restore(JSONObject saved) {
        utterances.clear(); hints.clear(); hintedUpTo = 0; nextHint = 1; nextUtterance = 1;
        id = saved.optString("id", UUID.randomUUID().toString());
        created = saved.optLong("created", System.currentTimeMillis());
        JSONArray lines = saved.optJSONArray("utterances"), cards = saved.optJSONArray("hints");
        if (lines != null) for (int i = 0; i < Math.min(lines.length(), 2000); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) continue;
            put(line, "final", true); utterances.add(line);
            nextUtterance = Math.max(nextUtterance, line.optInt("id") + 1);
        }
        if (cards != null) for (int i = 0; i < Math.min(cards.length(), 500); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) continue;
            if (busy(card)) put(card, "status", "stopped");
            card.remove("request"); hints.add(card);
            hintedUpTo = Math.max(hintedUpTo, card.optInt("toId"));
            nextHint = Math.max(nextHint, card.optInt("id") + 1);
        }
    }
    private JSONObject open() {
        return !utterances.isEmpty() && !utterances.get(utterances.size() - 1).optBoolean("final") ? utterances.get(utterances.size() - 1) : null;
    }
    private JSONObject waiting() {
        for (int i = hints.size() - 1; i >= 0; i--) if (hints.get(i).optString("status").equals("waiting")) return hints.get(i);
        return null;
    }
    private void resumeWaiting(int latest) {
        JSONObject newest = waiting();
        // Older concurrent cards retain their original range; only the newest absorbs the follow-up.
        for (JSONObject card : hints) if (card.optString("status").equals("waiting")) {
            start(card, card == newest ? Math.max(latest, card.optInt("toId")) : card.optInt("toId"), "auto", "");
        }
    }
    static boolean busy(JSONObject card) { return "capturing".equals(card.optString("status")) || "streaming".equals(card.optString("status")) || "waiting".equals(card.optString("status")); }
    String beginScreenshot() {
        for (JSONObject card : hints) if (card.optString("source").equals("screenshot") && busy(card)) throw new IllegalStateException("截图回答仍在进行，请等待完成或先停止提示");
        JSONObject card = create(0, 0);
        String request = UUID.randomUUID().toString();
        put(card, "source", "screenshot"); put(card, "status", "capturing"); put(card, "request", request);
        put(card, "text", ""); put(card, "reasoning", ""); put(card, "started", System.currentTimeMillis());
        output.changed(); return request;
    }
    void screenshot(String request, String image) {
        for (JSONObject card : hints) if (request.equals(card.optString("request")) && card.optString("status").equals("capturing")) {
            put(card, "status", "streaming"); put(card, "started", System.currentTimeMillis());
            JSONArray content = new JSONArray().put(json("type", "text", "text", "请识别当前截图中的题目、代码或问题，并直接给出完整答案。算法题请包含思路、代码与复杂度；看不清的内容请指出，不要编造。"))
                .put(json("type", "image_url", "image_url", json("url", image)));
            output.changed(); output.generate(request, new JSONArray().put(json("role", "user", "content", content))); return;
        }
    }
    void supplement(String text) {
        if (open() != null) throw new IllegalStateException("对方还在说，请等当前句子结束或停止监听后再补充，输入已保留");
        sentence(text, true);
    }
    void sentence(String text, boolean end) {
        text = text.substring(0, Math.min(text.length(), 12000));
        JSONObject line = open();
        if (line == null) {
            if (text.trim().isEmpty()) return;
            if (utterances.size() >= 2000) throw new IllegalStateException("本段转录已达 2000 句，请保存并新建面试");
            line = json("id", nextUtterance++, "text", text, "final", end); utterances.add(line);
        } else { put(line, "text", text); put(line, "final", end); }
        if (end && text.trim().isEmpty()) {
            utterances.remove(line);
            JSONObject pending = waiting();
            if (pending != null) resumeWaiting(pending.optInt("toId"));
        } else if (end) {
            JSONObject pending = waiting();
            if (pending != null) resumeWaiting(line.optInt("id"));
            else if (automatic && line.optInt("id") > hintedUpTo && meaningful(text) >= minChars) {
                start(create(hintedUpTo + 1, line.optInt("id")), line.optInt("id"), "auto", "");
            }
        } else if (automatic && meaningful(text) >= minChars) {
            for (JSONObject card : hints) {
                if (!card.optString("source").equals("auto") || !card.optString("status").equals("streaming") || line.optInt("id") <= card.optInt("toId")) continue;
                abort(card); put(card, "status", "waiting"); put(card, "text", ""); put(card, "reasoning", ""); card.remove("latencyMs");
            }
        }
        output.changed();
    }
    void listeningStopped() {
        JSONObject line = open();
        // Stopping must finish a withdrawn card, but must not unexpectedly start new paid requests.
        if (line != null) put(line, "final", true);
        JSONObject pending = waiting();
        if (pending != null) resumeWaiting(line == null ? pending.optInt("toId") : line.optInt("id"));
        output.changed();
    }
    void manual() {
        if (utterances.isEmpty()) throw new IllegalStateException("还没有识别到内容，可先听取声音或补充文字");
        int latest = utterances.get(utterances.size() - 1).optInt("id");
        for (int i = hints.size() - 1; i >= 0; i--) {
            JSONObject card = hints.get(i);
            if (card.optString("source").equals("screenshot") || !busy(card)) continue;
            if (card.optString("status").equals("streaming") && latest == card.optInt("toId")) return;
            start(card, Math.max(latest, card.optInt("toId")), "manual", ""); return;
        }
        if (latest > hintedUpTo) start(create(hintedUpTo + 1, latest), latest, "manual", "");
        else for (int i = hints.size() - 1; i >= 0; i--) {
            JSONObject card = hints.get(i);
            if (card.optString("source").equals("screenshot")) continue;
            start(card, card.optInt("toId"), "manual", card.optString("text")); break;
        }
    }
    private JSONObject create(int from, int to) {
        if (hints.size() >= 500) throw new IllegalStateException("本段提示已达 500 张，请保存并新建面试");
        JSONObject card = json("id", nextHint++, "fromId", from, "toId", to); hints.add(card); return card;
    }
    private void abort(JSONObject card) {
        String request = card.optString("request"); card.remove("request");
        if (!request.isEmpty()) output.cancel(request);
    }
    private void start(JSONObject card, int to, String source, String previous) {
        abort(card);
        String request = UUID.randomUUID().toString();
        put(card, "toId", to); put(card, "source", source); put(card, "status", "streaming");
        put(card, "text", ""); put(card, "reasoning", ""); put(card, "request", request);
        put(card, "started", System.currentTimeMillis()); card.remove("error"); card.remove("latencyMs");
        hintedUpTo = Math.max(hintedUpTo, to);
        output.changed(); output.generate(request, messages(card.optInt("fromId"), to, previous));
    }
    JSONArray messages(int from, int to, String previous) {
        List<String> context = new ArrayList<>(); int count = 0;
        for (int i = utterances.size() - 1; i >= 0 && context.size() < 20; i--) {
            JSONObject line = utterances.get(i); if (line.optInt("id") >= from) continue;
            String text = line.optString("text");
            if (text.trim().isEmpty()) continue;
            count += text.length();
            if (count > 1500 && !context.isEmpty()) break;
            context.add(0, text);
        }
        StringBuilder focus = new StringBuilder(); boolean unfinished = false;
        for (JSONObject line : utterances) if (line.optInt("id") >= from && line.optInt("id") <= to) {
            if (line.optString("text").trim().isEmpty()) continue;
            if (focus.length() > 0) focus.append('\n');
            focus.append(line.optString("text")); unfinished = !line.optBoolean("final");
        }
        String text = (context.isEmpty() ? "" : "【之前对方说过的话】\n" + String.join("\n", context) + "\n\n")
            + "【对方刚才说的话】" + (unfinished ? "（还没说完）" : "") + "\n" + focus;
        if (!previous.trim().isEmpty()) text += "\n\n【上一版提示】\n" + previous + "\n\n请换一个角度重新给出提示，不要重复上一版。";
        return new JSONArray().put(json("role", "user", "content", text));
    }
    void delta(String request, String kind, String text) {
        for (JSONObject card : hints) if (request.equals(card.optString("request"))) {
            if (kind.equals("notice")) { put(card, "notice", text); break; }
            String key = kind.equals("reasoning") ? "reasoning" : "text";
            String combined = card.optString(key) + text;
            if (combined.length() > 100000) { abort(card); put(card, "status", "error"); put(card, "error", "回答过长，已停止"); }
            else put(card, key, combined);
            if (!card.has("latencyMs") && key.equals("text")) put(card, "latencyMs", System.currentTimeMillis() - card.optLong("started"));
            output.changed(); return;
        }
    }
    void complete(String request, String error) {
        for (JSONObject card : hints) if (request.equals(card.optString("request"))) {
            card.remove("request"); put(card, "status", error == null ? "done" : "error");
            if (error != null) put(card, "error", error);
            output.changed(); return;
        }
    }
    void stopHints() { for (JSONObject card : hints) if (busy(card)) { abort(card); put(card, "status", "stopped"); } output.changed(); }
    String markdown() {
        StringBuilder text = new StringBuilder("# 面试记录\n\n");
        for (JSONObject line : utterances) {
            text.append("> ").append(line.optString("text").replace("\n", "\n> ")).append("\n\n");
            for (JSONObject card : hints) if (card.optInt("toId") == line.optInt("id") && (card.optString("status").equals("done") || card.optString("status").equals("stopped"))) {
                String answer = card.optString("text");
                if (!answer.isEmpty() && !answer.trim().equals("（无需回应）")) text.append(answer).append(card.optString("status").equals("stopped") ? "\n（已停止）" : "").append("\n\n");
            }
        }
        for (JSONObject card : hints) if (card.optString("source").equals("screenshot") && !card.optString("text").isEmpty()) {
            text.append("## 截图回答\n\n").append(card.optString("text"));
            if (!card.optString("status").equals("done")) text.append("\n（未完成）");
            text.append("\n\n");
        }
        return text.toString();
    }
}
