package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryRepairServiceTest {

    private DeliveryRepairService service(MaterialStore store, AiService ai, FfmpegTool ffmpeg) {
        return new DeliveryRepairService(store, ai, ffmpeg, new AppProps());
    }

    @Test
    void audioFailureUsesReadableBgmAsAutomaticRepair() throws Exception {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        Path audio = Files.createTempFile("repair-bgm-", ".mp3");
        Material bgm = new Material();
        bgm.setId(19L);
        bgm.setRole(MaterialRole.bgm);
        bgm.setFileType(Material.FileType.audio);
        bgm.setStatus(Material.Status.ready);
        bgm.setFilePath(audio.toString());
        when(store.findByFileType(Material.FileType.audio)).thenReturn(List.of(bgm));
        FfmpegTool.MediaInfo info = new FfmpegTool.MediaInfo();
        info.setHasAudio(true);
        info.setAudioDuration(1);
        when(ffmpeg.probe(audio.toString())).thenReturn(info);
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("音频阶段失败：无法生成可播放的混音轨");
        MixParams params = new MixParams();

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, params, new MixPlanner.Plan(), 0);

        assertTrue(assessment.isAutoFixable());
        assertEquals("replace-bgm", assessment.getRecommendedAction());
        assertTrue(service.applyAutomatic(params, assessment));
        assertEquals(19L, params.getBgmMaterialId());
        Files.deleteIfExists(audio);
    }

    @Test
    void audioFailureWithoutReadableSourceAwaitsHumanDecision() {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        when(store.findByFileType(Material.FileType.audio)).thenReturn(List.of());
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("成品质检未通过：audio：成片没有可播放的音频");

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, new MixParams(), new MixPlanner.Plan(), 0);

        assertFalse(assessment.isAutoFixable());
        assertTrue(assessment.needsHuman());
        assertTrue(assessment.getCandidateActions().contains("await-human-audio-choice"));
    }

    @Test
    void aiCannotDowngradeVerifiedAutomaticAudioRepairToHumanOnly() throws Exception {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        Path audio = Files.createTempFile("repair-ai-override-", ".mp3");
        Material bgm = new Material();
        bgm.setId(21L);
        bgm.setRole(MaterialRole.bgm);
        bgm.setFileType(Material.FileType.audio);
        bgm.setStatus(Material.Status.ready);
        bgm.setFilePath(audio.toString());
        FfmpegTool.MediaInfo info = new FfmpegTool.MediaInfo();
        info.setHasAudio(true);
        info.setAudioDuration(10);
        when(store.findByFileType(Material.FileType.audio)).thenReturn(List.of(bgm));
        when(ffmpeg.probe(audio.toString())).thenReturn(info);
        when(ai.askJson(any(), anyString(), anyString(), anyDouble(), anyInt(), isNull()))
                .thenReturn(new ObjectMapper().readTree("{\"assessment\":\"manual review\",\"recommendedAction\":\"await-human-audio-choice\"}"));
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("音频阶段失败：无法生成可播放的混音轨");

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, new MixParams(), new MixPlanner.Plan(), 0);

        assertTrue(assessment.isAutoFixable());
        assertEquals("replace-bgm", assessment.getRecommendedAction());
        Files.deleteIfExists(audio);
    }

    @Test
    void subtitleFailureCanDisableSubtitleBurnAndRetry() {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("字幕烧录失败，无法生成最终字幕输出");
        MixParams params = new MixParams();
        params.setAutoSubtitles(true);
        params.setBurnAiVoiceCaptions(true);

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, params, new MixPlanner.Plan(), 0);

        assertTrue(assessment.isAutoFixable());
        assertEquals("relax-subtitle", assessment.getRecommendedAction());
        assertTrue(service.applyAutomatic(params, assessment));
        assertFalse(params.getAutoSubtitles());
        assertFalse(params.getBurnAiVoiceCaptions());
    }

    @Test
    void duplicateFailureRegeneratesPlanInsteadOfIgnoringQualityIssue() {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("成品质检未通过：duplicate：同源片段时间重叠");
        MixParams params = new MixParams();

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, params, new MixPlanner.Plan(), 0);

        assertTrue(assessment.isAutoFixable());
        assertEquals("regenerate-plan", assessment.getRecommendedAction());
        assertTrue(service.applyAutomatic(params, assessment));
        assertNotNull(params.getSeed());
    }

    @Test
    void missingFfmpegRequiresHumanEnvironmentDecision() {
        MaterialStore store = mock(MaterialStore.class);
        AiService ai = mock(AiService.class);
        FfmpegTool ffmpeg = mock(FfmpegTool.class);
        DeliveryRepairService service = service(store, ai, ffmpeg);
        RenderService.RenderResult result = new RenderService.RenderResult();
        result.setError("找不到 ffmpeg。请安装并把 ffmpeg/ffprobe 加入 PATH。");

        DeliveryRepairService.RepairAssessment assessment = service.assess(result, new MixParams(), new MixPlanner.Plan(), 0);

        assertFalse(assessment.isAutoFixable());
        assertTrue(assessment.needsHuman());
        assertEquals("P0", assessment.getSeverity());
    }
}
