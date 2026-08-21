package com.douyin.mixcut.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MixParams, especially the autoSubtitles default change.
 */
class MixParamsTest {

    @Test
    void autoSubtitlesDefaultsToFalse() {
        MixParams params = new MixParams();
        // Before normalization, the field default is false
        assertFalse(params.getAutoSubtitles());
    }

    @Test
    void normalizedAutoSubtitlesStaysFalse() {
        MixParams params = new MixParams();
        params.setAutoSubtitles(null); // explicit null
        MixParams normalized = params.normalized();
        assertFalse(normalized.getAutoSubtitles());
    }

    @Test
    void autoSubtitlesCanBeExplicitlyEnabled() {
        MixParams params = new MixParams();
        params.setAutoSubtitles(true);
        MixParams normalized = params.normalized();
        assertTrue(normalized.getAutoSubtitles());
    }

    @Test
    void autoSubtitlesCanBeExplicitlyDisabled() {
        MixParams params = new MixParams();
        params.setAutoSubtitles(false);
        MixParams normalized = params.normalized();
        assertFalse(normalized.getAutoSubtitles());
    }

    @Test
    void explicitVoiceAlwaysUsesMaterialAudioMode() {
        MixParams params = new MixParams();
        params.setVoiceMaterialId(99L);
        params.setAudioMode("ai-voice");
        assertEquals("material-audio", params.normalized().getAudioMode());

        params.setAudioMode("original");
        assertEquals("material-audio", params.normalized().getAudioMode());
    }

    @Test
    void continuousDispatchMetadataSurvivesNormalization() {
        MixParams params = new MixParams();
        params.setContinuous(true);
        assertTrue(params.normalized().getContinuous());
    }

    @Test
    void normalizedPreservesOtherDefaults() {
        MixParams params = new MixParams().normalized();
        assertNotNull(params.getMinSec());
        assertNotNull(params.getMaxSec());
        assertEquals(50, params.getMinSec());
        assertEquals(150, params.getMaxSec());
        assertEquals(3.0, params.getSliceSec(), 0.01);
        assertEquals(5, params.getMaxSlicesPerMaterial());
    }

    @Test
    void dedupStrictnessNormalizesToStandardForInvalidValue() {
        MixParams params = new MixParams();
        params.setDedupStrictness("bogus");
        assertEquals("standard", params.normalized().getDedupStrictness());

        params.setDedupStrictness("strict");
        assertEquals("strict", params.normalized().getDedupStrictness());

        params.setDedupStrictness("off");
        assertEquals("off", params.normalized().getDedupStrictness());
    }

    @Test
    void rehookDefaultsAreSafeAndPreserved() {
        MixParams params = new MixParams().normalized();
        assertFalse(params.getAutoRehook());
        assertTrue(params.getBurnRehookText());
        assertTrue(params.getAllowSameSourceNonoverlap());
    }

    @Test
    void silentAudioModeIsAllowedWithoutChangingMode() {
        MixParams params = new MixParams();
        params.setAudioMode("silent");
        assertEquals("silent", params.normalized().getAudioMode());
    }

    @Test
    void folderReadStepsDropDuplicateAndInvalidOrders() {
        MixParams params = new MixParams();
        MixParams.FolderReadStep first = new MixParams.FolderReadStep();
        first.setOrder(1);
        first.setFolderId(10L);
        MixParams.FolderReadStep duplicate = new MixParams.FolderReadStep();
        duplicate.setOrder(1);
        duplicate.setFolderId(11L);
        MixParams.FolderReadStep invalid = new MixParams.FolderReadStep();
        invalid.setOrder(99);
        invalid.setFolderId(12L);
        params.setFolderReadSteps(java.util.List.of(first, duplicate, invalid));

        MixParams normalized = params.normalized();
        assertEquals(1, normalized.getFolderReadSteps().size());
        assertEquals(10L, normalized.getFolderReadSteps().get(0).getFolderId());
        assertEquals("读取步骤 1", normalized.getFolderReadSteps().get(0).getName());
    }

    @Test
    void strictDeliveryDefaultsToFalseForCompatibility() {
        MixParams params = new MixParams();
        assertFalse(params.getStrictDelivery());
    }

    @Test
    void normalizedStrictDeliveryDefaultsToFalse() {
        MixParams params = new MixParams();
        params.setStrictDelivery(null); // explicit null must not flip strict delivery on
        assertFalse(params.normalized().getStrictDelivery());
    }

    @Test
    void strictDeliveryCanBeExplicitlyEnabled() {
        MixParams params = new MixParams();
        params.setStrictDelivery(true);
        assertTrue(params.normalized().getStrictDelivery());
    }

    @Test
    void autonomyModeDefaultsToAssist() {
        MixParams params = new MixParams();
        assertEquals("assist", params.getAutonomyMode());
        assertEquals("assist", params.normalized().getAutonomyMode());
    }

    @Test
    void autonomyModeNormalizesInvalidValuesToAssist() {
        MixParams params = new MixParams();
        params.setAutonomyMode(null);
        assertEquals("assist", params.normalized().getAutonomyMode());

        params.setAutonomyMode("  ");
        assertEquals("assist", params.normalized().getAutonomyMode());

        params.setAutonomyMode("rogue");
        assertEquals("assist", params.normalized().getAutonomyMode());
    }

    @Test
    void autonomyModePreservedThroughNormalization() {
        MixParams params = new MixParams();
        params.setAutonomyMode("auto");
        assertEquals("auto", params.normalized().getAutonomyMode());

        params.setAutonomyMode("autonomous");
        assertEquals("autonomous", params.normalized().getAutonomyMode());
    }

    @Test
    void autonomousModeDefaultsStrictDeliveryOnWhenNotExplicitlySet() {
        MixParams params = new MixParams();
        params.setAutonomyMode("autonomous");
        params.setStrictDelivery(null); // 未显式填写 → 自主模式默认开启严格交付
        assertTrue(params.normalized().getStrictDelivery());
    }

    @Test
    void autonomousModeKeepsExplicitStrictDeliveryChoice() {
        MixParams params = new MixParams();
        params.setAutonomyMode("autonomous");
        params.setStrictDelivery(false); // 显式关闭优先于自主模式默认值
        assertFalse(params.normalized().getStrictDelivery());

        params.setStrictDelivery(true);
        assertTrue(params.normalized().getStrictDelivery());
    }

    @Test
    void assistAndAutoModesKeepLegacyStrictDeliveryDefault() {
        MixParams params = new MixParams();
        params.setAutonomyMode("assist");
        assertFalse(params.normalized().getStrictDelivery());

        params.setAutonomyMode("auto");
        assertFalse(params.normalized().getStrictDelivery());
    }

    @Test
    void legacyParamsWithoutAutonomyModeKeepStrictDeliveryOff() {
        // 旧版客户端不发送 autonomyMode；strictDelivery 显式置 null 时必须保持关闭，兼容旧行为。
        MixParams params = new MixParams();
        params.setAutonomyMode(null);
        params.setStrictDelivery(null);
        MixParams normalized = params.normalized();
        assertEquals("assist", normalized.getAutonomyMode());
        assertFalse(normalized.getStrictDelivery());
    }
}
