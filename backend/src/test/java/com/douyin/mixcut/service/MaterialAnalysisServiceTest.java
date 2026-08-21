package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialAnalysis;
import com.douyin.mixcut.domain.MaterialSegment;
import com.douyin.mixcut.domain.UseCase;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.repository.MaterialAnalysisStore;
import com.douyin.mixcut.repository.MaterialSegmentStore;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.MaterialTranscriptStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialAnalysisServiceTest {

    @Mock private MaterialStore materialStore;
    @Mock private MaterialAnalysisStore analysisStore;
    @Mock private MaterialSegmentStore segmentStore;
    @Mock private MaterialTranscriptStore transcriptStore;
    @Mock private MaterialDiagnosisService diagnosisService;
    @Mock private AiService aiService;
    @Mock private FfmpegTool ffmpeg;

    @TempDir Path tempDir;

    private final ObjectMapper om = new ObjectMapper();

    private Material video() {
        Material m = new Material();
        m.setId(100L);
        m.setName("sample.mp4");
        m.setFilePath("C:/data/materials/uploads/sample.mp4");
        m.setFileType(Material.FileType.video);
        m.setStatus(Material.Status.ready);
        m.setDurationSec(10.0);
        m.setTags("美妆,护肤");
        return m;
    }

    private MaterialAnalysisService service() {
        AppProps props = new AppProps();
        props.setDataDir(tempDir.resolve("data").toString());
        props.setCacheDir(tempDir.resolve("cache").toString());
        return new MaterialAnalysisService(materialStore, analysisStore, segmentStore, transcriptStore,
                diagnosisService, aiService, ffmpeg, props, Runnable::run);
    }

    @Test
    void fallsBackToUniformSegmentsAndExistingTagsWhenSceneAndAiUnavailable() {
        Material material = video();
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));

        MaterialAnalysis existing = new MaterialAnalysis();
        existing.setId(1L);
        existing.setMaterialId(100L);
        when(analysisStore.findByMaterialId(100L)).thenReturn(Optional.of(existing));
        when(analysisStore.save(any(MaterialAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptStore.findByMaterialId(100L)).thenReturn(Optional.empty());
        when(diagnosisService.readOcrTexts(material)).thenReturn(List.of());
        when(diagnosisService.getCachedTranscript(100L)).thenReturn(List.of());
        when(aiService.askJson(any(UseCase.class), anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(null);
        when(ffmpeg.detectSceneCuts(anyString(), anyDouble())).thenReturn(List.of());

        service().runAnalysis(100L, 1L);

        ArgumentCaptor<MaterialAnalysis> saved = ArgumentCaptor.forClass(MaterialAnalysis.class);
        verify(analysisStore).save(saved.capture());
        assertEquals("completed", saved.getValue().getStatus());
        assertEquals("fallback", saved.getValue().getSource());
        assertTrue(saved.getValue().getTagsJson().contains("美妆"), "AI 不可用时应回退到既有素材标签");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MaterialSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(segmentStore).insertBatch(segments.capture());
        assertFalse(segments.getValue().isEmpty(), "场景检测失败时应有均匀切片兜底");
        verify(segmentStore).deleteByMaterialId(100L);
    }

    @Test
    void persistsRepresentativeAndFixedSampleFrames() throws Exception {
        Material material = video();
        material.setFilePath(tempDir.resolve("sample.mp4").toString());
        Files.writeString(Path.of(material.getFilePath()), "video");
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(analysisStore.findByMaterialId(100L)).thenReturn(Optional.of(existingAnalysis()));
        when(analysisStore.save(any(MaterialAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptStore.findByMaterialId(100L)).thenReturn(Optional.empty());
        when(diagnosisService.readOcrTexts(material)).thenReturn(List.of());
        when(diagnosisService.getCachedTranscript(100L)).thenReturn(List.of());
        when(aiService.askJson(any(UseCase.class), anyString(), anyString(), anyDouble(), anyInt(), any())).thenReturn(null);
        FfmpegTool.SceneCut cut = new FfmpegTool.SceneCut();
        cut.setTime(5.0);
        cut.setScore(0.8);
        when(ffmpeg.detectSceneCuts(anyString(), anyDouble())).thenReturn(List.of(cut));
        doAnswer(inv -> {
            Path dst = inv.getArgument(1);
            Files.writeString(dst, "frame");
            return true;
        }).when(ffmpeg).analysisFrame(anyString(), any(Path.class), anyDouble());

        service().runAnalysis(100L, 1L);

        ArgumentCaptor<List<MaterialSegment>> captured = ArgumentCaptor.forClass(List.class);
        verify(segmentStore).insertBatch(captured.capture());
        assertEquals(2, captured.getValue().size());
        assertEquals(2.5, captured.getValue().get(0).getRepresentativeFrameAtSec());
        assertTrue(captured.getValue().get(0).getRepresentativeFrameUrl().startsWith("/files/thumbs/"));
        ArgumentCaptor<MaterialAnalysis> analysis = ArgumentCaptor.forClass(MaterialAnalysis.class);
        verify(analysisStore).save(analysis.capture());
        assertEquals(2, ((com.fasterxml.jackson.databind.node.ArrayNode) om.readTree(analysis.getValue().getSampleFramesJson())).size());
    }

    @Test
    void reusesExistingFrameFilesWithoutCallingFfmpegAgain() throws Exception {
        Material material = video();
        material.setFilePath(tempDir.resolve("sample.mp4").toString());
        Files.writeString(Path.of(material.getFilePath()), "video");
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));
        when(analysisStore.findByMaterialId(100L)).thenReturn(Optional.of(existingAnalysis()));
        when(analysisStore.save(any(MaterialAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptStore.findByMaterialId(100L)).thenReturn(Optional.empty());
        when(diagnosisService.readOcrTexts(material)).thenReturn(List.of());
        when(diagnosisService.getCachedTranscript(100L)).thenReturn(List.of());
        when(aiService.askJson(any(UseCase.class), anyString(), anyString(), anyDouble(), anyInt(), any())).thenReturn(null);
        when(ffmpeg.detectSceneCuts(anyString(), anyDouble())).thenReturn(List.of());
        doAnswer(inv -> {
            Path dst = inv.getArgument(1);
            Files.writeString(dst, "frame");
            return true;
        }).when(ffmpeg).analysisFrame(anyString(), any(Path.class), anyDouble());

        service().runAnalysis(100L, 1L);
        service().runAnalysis(100L, 1L);

        verify(ffmpeg, times(6)).analysisFrame(anyString(), any(Path.class), anyDouble());
    }

    private MaterialAnalysis existingAnalysis() {
        MaterialAnalysis existing = new MaterialAnalysis();
        existing.setId(1L);
        existing.setMaterialId(100L);
        return existing;
    }

    @Test
    void usesAiTagsWhenStructuredJsonReturned() throws Exception {
        Material material = video();
        when(materialStore.findById(100L)).thenReturn(Optional.of(material));

        MaterialAnalysis existing = new MaterialAnalysis();
        existing.setId(1L);
        existing.setMaterialId(100L);
        when(analysisStore.findByMaterialId(100L)).thenReturn(Optional.of(existing));
        when(analysisStore.save(any(MaterialAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptStore.findByMaterialId(100L)).thenReturn(Optional.empty());
        when(diagnosisService.readOcrTexts(material)).thenReturn(List.of("限时 9.9"));
        when(diagnosisService.getCachedTranscript(100L)).thenReturn(List.of());
        JsonNode aiTags = om.readTree("{\"tags\":[\"带货\",\"促销\"]}");
        when(aiService.askJson(any(UseCase.class), anyString(), anyString(), anyDouble(), anyInt(), any()))
                .thenReturn(aiTags);
        when(ffmpeg.detectSceneCuts(anyString(), anyDouble())).thenReturn(List.of());

        service().runAnalysis(100L, 1L);

        ArgumentCaptor<MaterialAnalysis> saved = ArgumentCaptor.forClass(MaterialAnalysis.class);
        verify(analysisStore).save(saved.capture());
        assertEquals("completed", saved.getValue().getStatus());
        assertTrue(saved.getValue().getTagsJson().contains("带货"), "结构化 AI 标签应被持久化");
        assertNotNull(saved.getValue().getOcrTextsJson());
    }
}
