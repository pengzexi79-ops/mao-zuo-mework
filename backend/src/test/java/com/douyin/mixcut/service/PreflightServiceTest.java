package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.AudioContract;
import com.douyin.mixcut.dto.PreflightResult;
import com.douyin.mixcut.external.ProcessRegistry;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class PreflightServiceTest {
    private final PreflightService service = new PreflightService();

    @Test
    void audioModesAreExplicitAndSilentDoesNotRequireExternalAudio() {
        for (String mode : new String[]{"silent", "original", "material-audio", "ai-voice"}) {
            MixPlanner.Plan plan = plan(81, 50, 150, true);
            plan.setRequiresExternalAudio(true);
            MixParams params = params("standard");
            params.setAudioMode(mode);
            PreflightResult result = service.evaluateAudio(plan, params, null, ProcessRegistry.CancellationContext.none());
            assertEquals(mode, result.getAudio().getMode());
            if ("silent".equals(mode) || "original".equals(mode)) {
                assertEquals("not_required", result.getAudio().getCoverageStatus());
                assertFalse(result.getBlockers().stream().anyMatch(i -> "audio.missing_source".equals(i.getCode())));
            }
        }
    }

    @Test
    void validMeasuredContractIsReady() {
        AudioContractService contracts = mock(AudioContractService.class);
        AudioContract valid = new AudioContract();
        valid.setReadable(true);
        valid.setHasAudio(true);
        valid.setOutputDuration(81);
        valid.setStartSec(0);
        valid.setEndSec(81);
        valid.setSampleRate(48000);
        valid.setChannels(2);
        valid.setCodec("aac");
        when(contracts.inspect(eq("bgm.wav"), anyDouble(), eq("bgm"), any())).thenReturn(valid);
        when(contracts.validate(eq(valid), anyDouble())).thenReturn(java.util.List.of());
        MixPlanner.Plan plan = plan(81, 50, 150, true);
        plan.setRequiresExternalAudio(true);
        plan.setBgmPath("bgm.wav");
        MixParams params = params("standard");
        params.setAudioMode("material-audio");
        PreflightResult result = service.evaluateAudio(plan, params, contracts, ProcessRegistry.CancellationContext.none());
        assertEquals(PreflightResult.READY, result.getAudio().getStatus());
        assertEquals("ready", result.getAudio().getCoverageStatus());
        assertTrue(result.getAudio().isBgmPresent());
        assertTrue(result.getAudio().getContractCodes().isEmpty());
    }

    @Test
    void invalidMeasuredContractUsesStableCodesAsBlockers() {
        AudioContractService contracts = mock(AudioContractService.class);
        AudioContract invalid = new AudioContract();
        invalid.setReadable(false);
        invalid.setHasAudio(false);
        when(contracts.inspect(eq("voice.wav"), anyDouble(), eq("voice"), any())).thenReturn(invalid);
        when(contracts.validate(eq(invalid), anyDouble())).thenReturn(java.util.List.of("AUDIO_STREAM_MISSING", "AUDIO_NOT_READABLE", "AUDIO_DURATION_MISMATCH"));
        MixPlanner.Plan plan = plan(81, 50, 150, true);
        plan.setRequiresExternalAudio(true);
        plan.setVoicePath("voice.wav");
        plan.setVoiceDurationSec(81);
        MixParams params = params("standard");
        params.setAudioMode("ai-voice");
        PreflightResult result = service.evaluateAudio(plan, params, contracts, ProcessRegistry.CancellationContext.none());
        assertEquals(PreflightResult.BLOCKED, result.getStatus());
        assertTrue(result.getAudio().getContractCodes().contains("AUDIO_STREAM_MISSING"));
        assertTrue(result.getBlockers().stream().anyMatch(i -> i.getCode().contains("audio.contract.audio_stream_missing")));
    }

    @Test

    void inRangePlanBelowRecommendedTargetIsNotBlocked() {
        MixPlanner.Plan plan = plan(81, 50, 150, true);
        plan.setTargetSec(100);

        PreflightResult result = service.evaluate(plan, params("standard"), true, true);

        assertNotEquals(PreflightResult.BLOCKED, result.getStatus());
        assertTrue(result.getWarnings().stream().anyMatch(issue -> "duration.below_target".equals(issue.getCode())));
    }

    @Test
    void duplicatePlanReportsDedupeInsteadOfDuration() {
        MixPlanner.Plan plan = plan(81, 50, 150, false);
        plan.getNotes().add("当前素材变体含 2 段完全重复，以及 1 处同源重叠；已在渲染前拒绝");

        PreflightResult result = service.evaluate(plan, params("strict"), true, true);

        assertEquals(PreflightResult.BLOCKED, result.getStatus());
        assertTrue(result.getBlockers().stream().anyMatch(issue -> "dedupe.conflict".equals(issue.getCode())));
        assertFalse(result.getBlockers().stream().anyMatch(issue -> "duration.below_min".equals(issue.getCode())));
    }

    @Test
    void missingMaterialAudioReportsAudioBlocker() {
        MixPlanner.Plan plan = plan(81, 50, 150, true);
        plan.setRequiresExternalAudio(true);

        PreflightResult result = service.evaluate(plan, params("standard"), true, true);

        assertEquals("missing_source", result.getAudioCoverageStatus());
        assertTrue(result.getBlockers().stream().anyMatch(issue -> "audio.missing_source".equals(issue.getCode())));
    }

    @Test
    void shortVoiceWithoutBgmReportsCoverageBlocker() {
        MixPlanner.Plan plan = plan(81, 50, 150, true);
        plan.setRequiresExternalAudio(true);
        plan.setVoicePath("voice.wav");
        plan.setVoiceDurationSec(20);

        PreflightResult result = service.evaluate(plan, params("standard"), true, true);

        assertEquals("insufficient_voice", result.getAudioCoverageStatus());
        assertTrue(result.getBlockers().stream().anyMatch(issue -> "audio.insufficient_voice".equals(issue.getCode())));
    }

    @Test
    void runtimeToolsAreASeparateBlocker() {
        MixPlanner.Plan plan = plan(81, 50, 150, true);

        PreflightResult result = service.evaluate(plan, params("standard"), false, true);

        assertTrue(result.getBlockers().stream().anyMatch(issue -> "runtime.media_tools_unavailable".equals(issue.getCode())));
    }

    @Test
    void optedOutDeduplicationBecomesWarning() {
        MixPlanner.Plan plan = plan(81, 50, 150, false);
        plan.getNotes().add("当前素材变体含 1 段完全重复");

        PreflightResult result = service.evaluate(plan, params("off"), true, true);

        assertEquals(PreflightResult.WARNING, result.getStatus());
        assertTrue(result.getWarnings().stream().anyMatch(issue -> "dedupe.opted_out".equals(issue.getCode())));
        assertTrue(result.getBlockers().isEmpty());
    }

    private MixParams params(String dedup) {
        MixParams params = new MixParams();
        params.setMinSec(50);
        params.setMaxSec(150);
        params.setTargetSec(100);
        params.setDedupStrictness(dedup);
        return params.normalized();
    }

    private MixPlanner.Plan plan(double planned, double min, double max, boolean unique) {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setPlannedSec(planned);
        plan.setMinSec(min);
        plan.setTargetSec(Math.min(max, 100));
        plan.setInternallyUnique(unique);
        MixPlanner.Segment segment = new MixPlanner.Segment();
        segment.setMaterialId(1L);
        segment.setDuration(planned);
        plan.getSegments().add(segment);
        return plan;
    }
}
