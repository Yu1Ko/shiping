package cn.shiping.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class InterviewEngineTest {
    private final List<String> requests = new ArrayList<>(), cancelled = new ArrayList<>();
    private JSONArray messages;
    private InterviewEngine engine() {
        return new InterviewEngine(new InterviewEngine.Output() {
            public void generate(String id, JSONArray input) { requests.add(id); messages = input; }
            public void cancel(String id) { cancelled.add(id); }
            public void changed() {}
        });
    }
    @Test public void automaticWaitsForFinalRewritesAndRejectsStaleTokens() throws Exception {
        InterviewEngine e = engine();
        e.sentence("请解释线程池", false); assertTrue(requests.isEmpty());
        e.sentence("请解释线程池", true); assertEquals(1, requests.size());
        String first = requests.get(0); e.delta(first, "text", "旧答案");
        e.sentence("以及拒绝策略", false);
        assertEquals(first, cancelled.get(0));
        assertEquals("waiting", e.snapshot().getJSONArray("hints").getJSONObject(0).getString("status"));
        e.delta(first, "text", "不能泄漏"); e.complete(first, null);
        e.sentence("以及拒绝策略", true); assertEquals(2, requests.size());
        assertTrue(messages.toString().contains("请解释线程池"));
        assertTrue(messages.toString().contains("以及拒绝策略"));
        e.delta(requests.get(1), "text", "新答案"); e.complete(requests.get(1), null);
        JSONObject card = e.snapshot().getJSONArray("hints").getJSONObject(0);
        assertEquals("新答案", card.getString("text")); assertEquals("done", card.getString("status"));
        assertEquals(1, e.snapshot().getJSONArray("hints").length());
    }
    @Test public void manualIncludesPartialAndRewritesPreviousWithoutDuplicateCards() throws Exception {
        InterviewEngine e = engine(); e.automatic = false;
        e.sentence("介绍一下你的项目", false); e.manual();
        assertTrue(messages.toString().contains("还没说完"));
        e.delta(requests.get(0), "text", "项目重点"); e.complete(requests.get(0), null);
        e.manual(); assertTrue(messages.toString().contains("上一版提示"));
        assertEquals(1, e.snapshot().getJSONArray("hints").length());
        e.stopHints(); e.delta(requests.get(1), "text", "迟到文本");
        assertEquals("", e.snapshot().getJSONArray("hints").getJSONObject(0).getString("text"));
    }
    @Test public void shortUtterancesAndEmptyFinalDoNotLoseWaitingQuestion() throws Exception {
        InterviewEngine e = engine(); e.sentence("嗯。", true); assertTrue(requests.isEmpty());
        e.sentence("如何实现一致性", true); e.sentence("补充一个问题", false);
        e.automatic = false; e.sentence("", true);
        assertEquals(2, requests.size()); assertTrue(messages.toString().contains("如何实现一致性"));
        assertEquals(2, e.snapshot().getJSONArray("utterances").length());
    }
    @Test public void restoreDoesNotRestartNetworkAndExportOmitsFailedHints() throws Exception {
        InterviewEngine e = engine(); e.sentence("什么是死锁", true);
        JSONObject saved = new JSONObject(e.snapshot().toString());
        InterviewEngine restored = engine(); restored.restore(saved);
        assertEquals("stopped", restored.snapshot().getJSONArray("hints").getJSONObject(0).getString("status"));
        assertEquals(1, requests.size()); assertTrue(restored.markdown().contains("什么是死锁"));
        assertFalse(restored.markdown().contains("streaming"));
    }
    @Test public void unicodePunctuationDoesNotTriggerHints() {
        assertEquals(0, InterviewEngine.meaningful(" ，。!? \n"));
        assertEquals(4, InterviewEngine.meaningful("线程，池？A"));
    }
    @Test public void supplementCannotOverwriteOpenAsrSentence() throws Exception {
        InterviewEngine e = engine(); e.sentence("正在解释原问题", false);
        try { e.supplement("手工补充"); fail(); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("输入已保留")); }
        e.sentence("正在解释原问题以及背景", true);
        assertEquals("正在解释原问题以及背景", e.snapshot().getJSONArray("utterances").getJSONObject(0).getString("text"));
        e.supplement("手工补充"); assertEquals(2, e.snapshot().getJSONArray("utterances").length());
    }
    @Test public void allWithdrawnCardsResumeAndOnlyNewestAbsorbsFollowUp() throws Exception {
        InterviewEngine e = engine(); e.sentence("第一道完整问题", true); e.sentence("第二道完整问题", true);
        e.sentence("请补充复杂度", false); assertEquals(2, cancelled.size());
        e.sentence("请补充复杂度", true);
        JSONArray cards = e.snapshot().getJSONArray("hints");
        assertEquals("streaming", cards.getJSONObject(0).getString("status"));
        assertEquals("streaming", cards.getJSONObject(1).getString("status"));
        assertEquals(1, cards.getJSONObject(0).getInt("toId"));
        assertEquals(3, cards.getJSONObject(1).getInt("toId"));
        assertEquals(4, requests.size());
    }
    @Test public void screenshotDoesNotConsumeVoiceRangeOrGetOverwrittenByManualHint() throws Exception {
        InterviewEngine e = engine(); e.automatic = false;
        e.sentence("解释线程池", true); e.manual();
        String voice = requests.get(0); e.delta(voice, "text", "语音答案"); e.complete(voice, null);
        String capture = e.beginScreenshot();
        try { e.beginScreenshot(); fail("Reject duplicate capture while busy"); } catch (IllegalStateException expected) { }
        e.screenshot(capture, "data:image/jpeg;base64,test-only");
        assertTrue(messages.toString().contains("image_url"));
        assertFalse(e.snapshot().toString().contains("base64"));
        e.manual();
        assertEquals(2, e.snapshot().getJSONArray("hints").length());
        assertTrue(messages.toString().contains("语音答案"));
        assertFalse(cancelled.contains(capture));
        e.delta(capture, "text", "截图答案"); e.complete(capture, null);
        assertTrue(e.markdown().contains("截图答案"));
        assertEquals("screenshot", e.snapshot().getJSONArray("hints").getJSONObject(1).getString("source"));
    }
    @Test public void cancelledCaptureCannotSendLateImageAndScreenshotOnlyHistoryRestores() throws Exception {
        InterviewEngine e = engine(); String capture = e.beginScreenshot();
        e.stopHints(); e.screenshot(capture, "data:image/jpeg;base64,late");
        assertTrue(requests.isEmpty());
        capture = e.beginScreenshot(); e.screenshot(capture, "data:image/jpeg;base64,test");
        e.delta(capture, "text", "只有截图的答案"); e.complete(capture, null);
        InterviewEngine restored = engine(); restored.restore(new JSONObject(e.snapshot().toString()));
        assertTrue(restored.markdown().contains("只有截图的答案"));
        assertEquals(0, restored.snapshot().getJSONArray("utterances").length());
        restored.sentence("第一句语音问题", true);
        assertEquals(1, restored.snapshot().getJSONArray("hints").getJSONObject(2).getInt("fromId"));
    }
}
