package com.douyin.mixcut.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MaterialTranscript domain model.
 */
class MaterialTranscriptTest {

    @Test
    void defaultsAreCorrect() {
        MaterialTranscript transcript = new MaterialTranscript();
        assertEquals("zh", transcript.getLanguage());
        assertEquals("pending", transcript.getStatus());
        assertNull(transcript.getModel());
        assertNull(transcript.getCues());
        assertNull(transcript.getError());
    }

    @Test
    void allFieldsCanBeSet() {
        MaterialTranscript transcript = new MaterialTranscript();
        transcript.setId(1L);
        transcript.setMaterialId(100L);
        transcript.setLanguage("en");
        transcript.setModel("whisper");
        transcript.setCues("[{\"start\":0.0,\"end\":2.5,\"text\":\"hello\"}]");
        transcript.setStatus("completed");
        transcript.setError(null);
        transcript.setCreatedAt(LocalDateTime.now());
        transcript.setUpdatedAt(LocalDateTime.now());

        assertEquals(1L, transcript.getId());
        assertEquals(100L, transcript.getMaterialId());
        assertEquals("en", transcript.getLanguage());
        assertEquals("whisper", transcript.getModel());
        assertEquals("[{\"start\":0.0,\"end\":2.5,\"text\":\"hello\"}]", transcript.getCues());
        assertEquals("completed", transcript.getStatus());
        assertNull(transcript.getError());
        assertNotNull(transcript.getCreatedAt());
        assertNotNull(transcript.getUpdatedAt());
    }

    @Test
    void statusTransitionsArePossible() {
        MaterialTranscript transcript = new MaterialTranscript();
        assertEquals("pending", transcript.getStatus());

        transcript.setStatus("running");
        assertEquals("running", transcript.getStatus());

        transcript.setStatus("completed");
        assertEquals("completed", transcript.getStatus());

        transcript.setStatus("failed");
        assertEquals("failed", transcript.getStatus());
    }
}
