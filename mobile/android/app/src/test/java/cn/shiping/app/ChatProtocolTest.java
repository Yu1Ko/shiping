package cn.shiping.app;

import org.junit.Test;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ChatProtocolTest {
    @Test public void readsChineseReasoningAndAnswer() throws Exception {
        String stream = ": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"分析\"}}]}\r\n\r\n"
            + "data:{\"choices\":[{\"delta\":{\"content\":\"答案✓\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
        List<String> result = new ArrayList<>();
        ChatProtocol.readStream(new BufferedReader(new StringReader(stream)), (kind, value) -> result.add(kind + ":" + value));
        assertEquals(java.util.Arrays.asList("reasoning:分析", "text:答案✓"), result);
    }
    @Test public void rejectsTruncatedStreamsInsteadOfReportingSuccess() {
        assertThrows(Exception.class, () -> ChatProtocol.readStream(new BufferedReader(new StringReader("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")), (kind, value) -> {}));
    }
    @Test public void supportsDoneWithoutTrailingNewline() throws Exception {
        ChatProtocol.readStream(new BufferedReader(new StringReader("data: [DONE]")), (kind, value) -> fail());
    }
    @Test public void refusesUnsafeEndpoints() {
        for (String base : new String[]{"http://api.test/v1", "https://key@api.test/v1", "https://api.test/v1?key=secret", "https://api.test/v1#key"}) {
            assertThrows(IllegalArgumentException.class, () -> ChatProtocol.endpoint(base, "models"));
        }
        assertEquals("https://api.test/v1/models", ChatProtocol.endpoint("https://api.test/v1/chat/completions", "models").toString());
    }
    @Test public void thinkingMatchesProviderAndRejectPolicy() throws Exception {
        JSONObject body = new JSONObject();
        ChatProtocol.thinking(body, new JSONObject().put("baseURL", "https://openrouter.ai/api/v1").put("model", "openai/gpt-4.1"));
        assertFalse(body.getJSONObject("reasoning").getBoolean("enabled"));
        assertTrue(ChatProtocol.thinkingRejected(400, "Unsupported reasoning_effort"));
        assertFalse(ChatProtocol.thinkingRejected(401, "reasoning"));
        assertFalse(ChatProtocol.thinkingRejected(400, "invalid image"));
    }
    @Test public void boundsInputWithoutReadingWholeDocument() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[100]);
        assertEquals(12, BoundedInput.read(input, 12).length);
        assertEquals(88, input.available());
    }
}
