package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.JobOutput;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.OutputEditSession;
import com.douyin.mixcut.domain.OutputVersion;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.repository.Repositories.JobOutputRepo;
import com.douyin.mixcut.repository.Repositories.OutputEditSessionRepo;
import com.douyin.mixcut.repository.Repositories.OutputVersionRepo;
import com.douyin.mixcut.external.FfmpegTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutputEditorServiceTest {
    @Mock private OutputEditSessionRepo sessionRepo;
    @Mock private OutputVersionRepo versionRepo;
    @Mock private JobOutputRepo outputRepo;
    @Mock private MaterialStore materialStore;
    @Mock private RenderService renderService;
    @Mock private DeliveryRepairService deliveryRepairService;
    @Mock private FfmpegTool ffmpeg;
    @Mock private OutputEditorPersistenceService persistenceService;

    private OutputEditorService service() {
        return serviceWith(Runnable::run);
    }

    private OutputEditorService serviceWith(Executor executor) {
        return new OutputEditorService(sessionRepo, versionRepo, outputRepo, materialStore, renderService,
                deliveryRepairService, ffmpeg, new ObjectMapper(), persistenceService, executor);
    }

    @Test
    void rejectsCrossOutputSessionAccess() {
        OutputEditSession session = new OutputEditSession();
        session.setId(9L);
        session.setJobId(7L);
        session.setIdx(2);
        when(sessionRepo.findById(9L)).thenReturn(Optional.of(session));

        assertThrows(IllegalArgumentException.class, () -> service().verifySession(9L, 7L, 1));
    }

    @Test
    void requiresExplicitConfirmationBeforeReplacingCurrentOutput() {
        assertThrows(IllegalArgumentException.class, () -> service().apply(9L, false));
    }

    @Test
    void rejectsCrawledMaterialInLocalEditorPlan() {
        OutputEditSession session = draftSession("{\"segments\":[]}", "{\"materialSourceMode\":\"local\"}");
        Material crawl = material(31L, Material.Source.crawl, 10.0);
        when(sessionRepo.findById(9L)).thenReturn(Optional.of(session));
        when(materialStore.findById(31L)).thenReturn(Optional.of(crawl));

        OutputEditorService.EditRequest request = requestWithSegment(31L, 0, 3, true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service().save(9L, request));
        assertEquals("当前为仅本地素材模式，公开来源素材必须先替换或移除", error.getMessage());
    }

    @Test
    void rejectsVideoTrimOutsideSourceDuration() {
        OutputEditSession session = draftSession("{\"segments\":[]}", "{\"materialSourceMode\":\"local\"}");
        Material local = material(32L, Material.Source.local, 5.0);
        when(sessionRepo.findById(9L)).thenReturn(Optional.of(session));
        when(materialStore.findById(32L)).thenReturn(Optional.of(local));

        OutputEditorService.EditRequest request = requestWithSegment(32L, 4.8, 1.0, true);
        assertEquals("视频裁剪超出素材范围：local.mp4",
                assertThrows(IllegalArgumentException.class, () -> service().save(9L, request)).getMessage());
    }

    @Test
    void executorRejectionMarksEditorSessionFailed() {
        OutputEditSession session = draftSession("{\"targetSec\":1,\"minSec\":1,\"segments\":[{\"materialId\":33,\"materialName\":\"local.mp4\",\"filePath\":\"C:/local.mp4\",\"kind\":\"video\",\"sourceStart\":0,\"duration\":1,\"sourceDuration\":2,\"slot\":\"body\"}]}", "{\"materialSourceMode\":\"local\",\"audioMode\":\"silent\"}");
        Material local = material(33L, Material.Source.local, 2.0);
        when(sessionRepo.findById(9L)).thenReturn(Optional.of(session));
        when(materialStore.findById(33L)).thenReturn(Optional.of(local));
        when(sessionRepo.save(any(OutputEditSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> serviceWith(rejecting).render(9L));
        assertEquals("候选渲染队列繁忙，请稍后重新生成候选", error.getMessage());
        assertEquals("failed", session.getStatus());
    }

    @Test
    void appliesOnlyPassedCandidateAndRetainsPreviousVersionForRollback() {
        OutputEditSession session = new OutputEditSession();
        session.setId(9L);
        session.setJobId(7L);
        session.setIdx(1);
        session.setStatus("passed");
        session.setCandidateVersionId(22L);
        session.setPlanSnapshot("{\"segments\":[]}");
        session.setParamsSnapshot("{}");

        JobOutput output = new JobOutput();
        output.setId(11L);
        output.setJobId(7L);
        output.setIdx(1);
        OutputVersion previous = version(21L, 1, "passed");
        previous.setJobOutputId(11L);
        OutputVersion candidate = version(22L, 2, "passed");
        candidate.setFilePath("C:/output/candidate.mp4");
        candidate.setDurationSec(8.0);

        when(sessionRepo.findById(9L)).thenReturn(Optional.of(session));
        when(versionRepo.findById(22L)).thenReturn(Optional.of(candidate));
        when(outputRepo.findByJobIdAndIdx(7L, 1)).thenReturn(Optional.of(output));
        when(versionRepo.findByJobIdAndIdxOrderByVersionNoAsc(7L, 1)).thenReturn(List.of(previous, candidate));
        when(outputRepo.save(any(JobOutput.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().apply(9L, true);

        assertEquals("rolled_back", previous.getStatus());
        assertEquals(null, previous.getJobOutputId());
        assertEquals(11L, candidate.getJobOutputId());
        assertEquals("applied", session.getStatus());
        verify(versionRepo).save(previous);
        verify(versionRepo).save(candidate);
    }

    private OutputEditSession draftSession(String plan, String params) {
        OutputEditSession session = new OutputEditSession();
        session.setId(9L);
        session.setJobId(7L);
        session.setIdx(1);
        session.setStatus("draft");
        session.setPlanSnapshot(plan);
        session.setParamsSnapshot(params);
        return session;
    }

    private OutputEditorService.EditRequest requestWithSegment(Long materialId, double start,
                                                                double duration, boolean enabled) {
        OutputEditorService.EditSegment segment = new OutputEditorService.EditSegment();
        segment.setIndex(1);
        segment.setMaterialId(materialId);
        segment.setSourceStart(start);
        segment.setDuration(duration);
        segment.setEnabled(enabled);
        OutputEditorService.EditRequest request = new OutputEditorService.EditRequest();
        request.setSegments(List.of(segment));
        request.getAudio().setMode("silent");
        return request;
    }

    private Material material(Long id, Material.Source source, double duration) {
        Material material = new Material();
        material.setId(id);
        material.setName(source == Material.Source.crawl ? "crawl.mp4" : "local.mp4");
        material.setFilePath("C:/" + material.getName());
        material.setFileType(Material.FileType.video);
        material.setSource(source);
        material.setDurationSec(duration);
        material.setStatus(Material.Status.ready);
        return material;
    }

    private OutputVersion version(Long id, int versionNo, String status) {
        OutputVersion version = new OutputVersion();
        version.setId(id);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        return version;
    }
}
