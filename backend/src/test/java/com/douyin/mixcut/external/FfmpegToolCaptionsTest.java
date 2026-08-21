package com.douyin.mixcut.external;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegToolCaptionsTest {

    @Test
    void chunkCaptionTextSplitsLongChineseText() {
        List<String> chunks = FfmpegTool.chunkCaptionText("这是一段很长的中文口播字幕需要被合理拆分", 8);
        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.codePointCount(0, chunk.length()) <= 8);
        }
        assertEquals("这是一段很长的中文口播字幕需要被合理拆分", String.join("", chunks));
    }

    @Test
    void chunkCaptionTextKeepsShortTextAsSingleChunk() {
        List<String> chunks = FfmpegTool.chunkCaptionText("短字幕", 8);
        assertEquals(1, chunks.size());
        assertEquals("短字幕", chunks.get(0));
    }

    @Test
    void safeCaptionYStaysWithinSafeRegionAndStacksLines() {
        assertEquals(0.72, FfmpegTool.safeCaptionY(0, 1, 0.72), 0.001);
        double line0 = FfmpegTool.safeCaptionY(0, 3, 0.72);
        double line2 = FfmpegTool.safeCaptionY(2, 3, 0.72);
        assertTrue(line0 < line2, "earlier lines should sit higher (smaller y)");
        assertTrue(line0 >= 0.50 && line2 <= 0.85, "lines must stay within the safe region");
    }

    @Test
    void chunkedCaptionsFilterProducesMultipleDrawtextAndSafeY() {
        FfmpegTool tool = new FfmpegTool(null, null);
        FfmpegTool.Caption caption = new FfmpegTool.Caption();
        caption.setText("这是一段超过十六个字的中文口播字幕内容需要被拆分为多行显示");
        caption.setFrom(1.0);
        caption.setTo(3.0);

        String filter = tool.captionsFilter(List.of(caption), "", 32, "white", 16, 0.72);

        assertTrue(filter.contains("drawtext="));
        assertTrue(filter.contains("h*0.72"), "bottom line should land at the safe default y");
        long drawtextCount = filter.split("drawtext=", -1).length - 1;
        assertTrue(drawtextCount >= 2, "long caption should be chunked into multiple drawtext filters");
    }
}
