package com.douyin.mixcut.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialSegmentTest {

    @Test
    void defaultsAreCorrect() {
        MaterialSegment segment = new MaterialSegment();
        assertNull(segment.getMaterialId());
        assertNull(segment.getAnalysisId());
        assertNull(segment.getIdx());
        assertNull(segment.getStartSec());
        assertNull(segment.getEndSec());
        assertNull(segment.getScore());
    }

    @Test
    void allFieldsCanBeSet() {
        MaterialSegment segment = new MaterialSegment();
        segment.setId(1L);
        segment.setMaterialId(100L);
        segment.setAnalysisId(7L);
        segment.setIdx(2);
        segment.setStartSec(0.0);
        segment.setEndSec(3.0);
        segment.setDurationSec(3.0);
        segment.setScore(0.42);

        assertEquals(1L, segment.getId());
        assertEquals(100L, segment.getMaterialId());
        assertEquals(7L, segment.getAnalysisId());
        assertEquals(2, segment.getIdx());
        assertEquals(0.0, segment.getStartSec());
        assertEquals(3.0, segment.getEndSec());
        assertEquals(0.42, segment.getScore());
    }
}
