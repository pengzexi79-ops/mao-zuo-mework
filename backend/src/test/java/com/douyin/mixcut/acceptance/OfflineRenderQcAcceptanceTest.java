package com.douyin.mixcut.acceptance;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.external.ProcRunner;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.service.AiService;
import com.douyin.mixcut.service.DeliveryQcService;
import com.douyin.mixcut.service.DeliveryRepairService;
import com.douyin.mixcut.service.MaterialDiagnosisService;
import com.douyin.mixcut.service.MixPlanner;
import com.douyin.mixcut.service.RenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3-3 offline acceptance: use only checked-in media fixtures and the bundled ffmpeg runtime.
 * No Spring context, database, HTTP client, source-fixture write, or public output is involved.
 */
class OfflineRenderQcAcceptanceTest {
    @TempDir Path tempDir;

    @Test
    void rendersAudioVideoFixtureAndPublishesOnlyQcAcceptedOutput() throws Exception {
        Path projectRoot = OfflineAcceptanceSupport.projectRoot();
        AppProps props = OfflineAcceptanceSupport.props(tempDir, projectRoot);
        ProcRunner runner = new ProcRunner();
        assumeBundledMediaTools(props, runner);

        Path input = OfflineAcceptanceSupport.copyFixture("video_av", tempDir.resolve("inputs"));
        MaterialStore materials = mock(MaterialStore.class);
        Material source = videoMaterial(1L, input);
        when(materials.findById(1L)).thenReturn(Optional.of(source));
        FfmpegTool ffmpeg = new FfmpegTool(props, runner);
        RenderService service = renderService(props, ffmpeg, materials);

        RenderService.RenderResult result = service.render(plan(input, 1L), originalAudioParams(), "accepted-fixture", step -> { });

        assertTrue(result.isOk(), result.getError());
        assertTrue(List.of("pass", "warn").contains(result.getQcStatus()), "qc=" + result.getQcStatus());
        assertNotNull(result.getQcJson());
        assertNotNull(result.getPublicUrl());
        Path output = Path.of(result.getFilePath());
        OfflineAcceptanceSupport.assertUnder(tempDir, output);
        assertTrue(output.startsWith(props.output()));
        assertTrue(Files.isRegularFile(output));
        FfmpegTool.MediaInfo info = ffmpeg.probe(output.toString());
        assertTrue(info.isHasVideo());
        assertTrue(info.isHasAudio());
        assertTrue(result.getDurationSec() > 0);
    }

    @Test
    void isolatesBlackCandidateAfterRealQcFailureWithoutPublicUrl() throws Exception {
        Path projectRoot = OfflineAcceptanceSupport.projectRoot();
        AppProps props = OfflineAcceptanceSupport.props(tempDir, projectRoot);
        ProcRunner runner = new ProcRunner();
        assumeBundledMediaTools(props, runner);

        Path inputRoot = tempDir.resolve("inputs");
        Path black = OfflineAcceptanceSupport.copyFixture("video_black", inputRoot);
        Path bgm = OfflineAcceptanceSupport.copyFixture("audio_bgm", inputRoot);
        MaterialStore materials = mock(MaterialStore.class);
        when(materials.findById(2L)).thenReturn(Optional.of(videoMaterial(2L, black)));
        FfmpegTool ffmpeg = new FfmpegTool(props, runner);
        RenderService service = renderService(props, ffmpeg, materials);
        MixPlanner.Plan plan = plan(black, 2L);
        plan.setBgmPath(bgm.toString());

        RenderService.RenderResult result = service.render(plan, bgmParams(), "black-candidate", step -> { });

        assertFalse(result.isOk());
        assertTrue("fail".equals(result.getQcStatus()), "qc=" + result.getQcStatus() + ", error=" + result.getError());
        assertNotNull(result.getQcJson());
        assertTrue(result.getPublicUrl() == null || result.getPublicUrl().isBlank());
        Path candidate = Path.of(result.getFilePath());
        OfflineAcceptanceSupport.assertUnder(tempDir, candidate);
        assertTrue(candidate.startsWith(props.cache().resolve("qc-candidates")));
        assertTrue(Files.isRegularFile(candidate));
        assertFalse(Files.exists(props.output().resolve("black-candidate.mp4")));
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("隔离保存")));
    }

    @Test
    void repairsAudioFailureOnlyByReplacingWithLocalReadableBgm() throws Exception {
        Path projectRoot = OfflineAcceptanceSupport.projectRoot();
        AppProps props = OfflineAcceptanceSupport.props(tempDir, projectRoot);
        ProcRunner runner = new ProcRunner();
        assumeBundledMediaTools(props, runner);

        Path bgmPath = OfflineAcceptanceSupport.copyFixture("audio_bgm", tempDir.resolve("inputs"));
        Material bgm = new Material();
        bgm.setId(9L);
        bgm.setRole(MaterialRole.bgm);
        bgm.setFileType(Material.FileType.audio);
        bgm.setStatus(Material.Status.ready);
        bgm.setFilePath(bgmPath.toString());
        MaterialStore materials = mock(MaterialStore.class);
        when(materials.findByFileType(Material.FileType.audio)).thenReturn(List.of(bgm));
        FfmpegTool ffmpeg = new FfmpegTool(props, runner);
        DeliveryRepairService repair = new DeliveryRepairService(materials, mock(AiService.class), ffmpeg, props);
        RenderService.RenderResult failed = new RenderService.RenderResult();
        failed.setError("成品质检未通过：audio：成片没有可播放的音频");
        MixParams params = new MixParams();

        DeliveryRepairService.RepairAssessment assessment = repair.assess(failed, params, new MixPlanner.Plan(), 0);

        assertTrue(assessment.isAutoFixable());
        assertTrue("replace-bgm".equals(assessment.getRecommendedAction()));
        assertTrue(repair.applyAutomatic(params, assessment));
        assertTrue(Long.valueOf(9L).equals(params.getBgmMaterialId()));
        assertTrue("material-audio".equals(params.getAudioMode()));
    }

    private void assumeBundledMediaTools(AppProps props, ProcRunner runner) {
        assumeTrue(runner.available(props.getFfmpeg(), "-version"), "bundled ffmpeg is unavailable");
        assumeTrue(runner.available(props.getFfprobe(), "-version"), "bundled ffprobe is unavailable");
    }

    private RenderService renderService(AppProps props, FfmpegTool ffmpeg, MaterialStore materials) throws Exception {
        RenderService service = new RenderService(props, ffmpeg, mock(MaterialDiagnosisService.class), materials);
        Field qcField = RenderService.class.getDeclaredField("deliveryQc");
        qcField.setAccessible(true);
        qcField.set(service, new DeliveryQcService(props));
        return service;
    }

    private Material videoMaterial(long id, Path file) {
        Material material = new Material();
        material.setId(id);
        material.setFilePath(file.toString());
        material.setFileType(Material.FileType.video);
        material.setStatus(Material.Status.ready);
        return material;
    }

    private MixPlanner.Plan plan(Path video, long materialId) {
        MixPlanner.Plan plan = new MixPlanner.Plan();
        plan.setMinSec(5);
        plan.setPlannedSec(6);
        plan.setSemanticSegmentCount(1);
        plan.setHookStrategy("fixture");
        plan.setHookText("fixture hook");
        for (int index = 0; index < 3; index++) {
            MixPlanner.Segment segment = new MixPlanner.Segment();
            segment.setIndex(index + 1);
            segment.setMaterialId(materialId);
            segment.setMaterialName("fixture-video");
            segment.setFilePath(video.toString());
            segment.setKind("video");
            segment.setSourceStart(0);
            segment.setSourceDuration(2);
            segment.setDuration(2);
            segment.setSlot(index == 0 ? "hook" : "body");
            plan.getSegments().add(segment);
        }
        return plan;
    }

    private MixParams originalAudioParams() {
        MixParams params = baseParams();
        params.setAudioMode("original");
        return params;
    }

    private MixParams bgmParams() {
        MixParams params = baseParams();
        params.setAudioMode("material-audio");
        return params;
    }

    private MixParams baseParams() {
        MixParams params = new MixParams();
        params.setMinSec(5);
        params.setMaxSec(10);
        params.setWidth(320);
        params.setHeight(568);
        params.setFps(24.0);
        params.setBurnHookText(false);
        params.setBurnAiVoiceCaptions(false);
        params.setAutoSubtitles(false);
        return params;
    }
}
