package com.douyin.mixcut.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialAnalysisTest {

    @Test
    void defaultsAreCorrect() {
        MaterialAnalysis analysis = new MaterialAnalysis();
        assertEquals("pending", analysis.getStatus());
        assertNull(analysis.getSource());
        assertNull(analysis.getTagsJson());
        assertNull(analysis.getOcrTextsJson());
        assertNull(analysis.getSummary());
        assertNull(analysis.getError());
    }

    @Test
    void allFieldsCanBeSet() {
        MaterialAnalysis analysis = new MaterialAnalysis();
        analysis.setId(1L);
        analysis.setMaterialId(100L);
        analysis.setStatus("completed");
        analysis.setSource("scene");
        analysis.setTagsJson("[\"美妆\"]");
        analysis.setOcrTextsJson("[\"9.9\"]");
        analysis.setTranscriptStatus("completed");
        analysis.setSummary("识别 5 个片段");
        analysis.setIssuesJson("[]");
        analysis.setError(null);

        assertEquals(1L, analysis.getId());
        assertEquals(100L, analysis.getMaterialId());
        assertEquals("completed", analysis.getStatus());
        assertEquals("scene", analysis.getSource());
        assertEquals("[\"美妆\"]", analysis.getTagsJson());
        assertEquals("completed", analysis.getTranscriptStatus());
        assertEquals("识别 5 个片段", analysis.getSummary());
    }
}
