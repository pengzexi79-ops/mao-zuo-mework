package com.douyin.mixcut.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Material domain model with new user-explicit flags.
 */
class MaterialTest {

    @Test
    void muteOriginalAudioDefaultsToFalse() {
        Material material = new Material();
        assertFalse(material.getMuteOriginalAudio());
    }

    @Test
    void transcribeForSubtitlesDefaultsToFalse() {
        Material material = new Material();
        assertFalse(material.getTranscribeForSubtitles());
    }

    @Test
    void muteOriginalAudioCanBeSet() {
        Material material = new Material();
        material.setMuteOriginalAudio(true);
        assertTrue(material.getMuteOriginalAudio());
    }

    @Test
    void transcribeForSubtitlesCanBeSet() {
        Material material = new Material();
        material.setTranscribeForSubtitles(true);
        assertTrue(material.getTranscribeForSubtitles());
    }

    @Test
    void bothFlagsCanBeSetIndependently() {
        Material material = new Material();
        material.setMuteOriginalAudio(true);
        material.setTranscribeForSubtitles(false);
        assertTrue(material.getMuteOriginalAudio());
        assertFalse(material.getTranscribeForSubtitles());
    }
}
