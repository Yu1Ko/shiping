package cn.shiping.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptBufferTest {
    @Test public void consumedPartialDoesNotReappearOnRevisionOrFinal() {
        TranscriptBuffer buffer = new TranscriptBuffer();
        buffer.sentence("请介绍", false);
        assertTrue(buffer.consume("请介绍"));
        buffer.sentence("请介绍你的项目", false);
        assertEquals("你的项目", buffer.text());
        buffer.sentence("请介绍你的项目。", true);
        assertEquals("你的项目。\n", buffer.text());
        buffer.sentence("下个问题", true);
        assertEquals("你的项目。\n下个问题\n", buffer.text());
    }
    @Test public void concurrentAppendAndRejectedPrefixPreserveNewText() {
        TranscriptBuffer buffer = new TranscriptBuffer();
        buffer.sentence("第一句", true); buffer.sentence("第二句", false);
        assertTrue(buffer.consume("第一句\n第"));
        assertEquals("二句", buffer.text());
        assertFalse(buffer.consume("不匹配"));
        buffer.finish(); assertEquals("二句\n", buffer.text());
    }
}
