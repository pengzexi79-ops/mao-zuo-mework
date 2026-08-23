package com.douyin.mixcut.service;

import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.dto.PreflightResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreflightServiceTest {
    private final PreflightService service = new PreflightService();

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
