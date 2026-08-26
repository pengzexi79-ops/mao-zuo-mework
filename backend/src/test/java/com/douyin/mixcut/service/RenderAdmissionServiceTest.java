package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.Workflow;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.repository.MaterialStore;
import com.douyin.mixcut.dto.AdmissionSnapshot;
import com.douyin.mixcut.dto.PreflightResult;
import com.douyin.mixcut.external.FfmpegTool;
import com.douyin.mixcut.dto.EffectiveRenderConfig;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.Repositories.ProjectRepo;
import com.douyin.mixcut.repository.Repositories.WorkflowRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RenderAdmissionServiceTest {
    private ProjectRepo projectRepo;
    private WorkflowRepo workflowRepo;
    private RenderAdmissionService service;
    private SkillEngine skillEngine;
    private PreflightService preflightService;
    private FfmpegTool ffmpeg;
    private MaterialStore materialStore;
    private Project project;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        projectRepo = mock(ProjectRepo.class);
        workflowRepo = mock(WorkflowRepo.class);
        project = new Project(); project.setId(7L); project.setDefaultParams("{\"minSec\":50}");
        workflow = new Workflow(); workflow.setId(9L); workflow.setName("default"); workflow.setVersion("1"); workflow.setDef("{\"steps\":[]}");
        when(projectRepo.findById(7L)).thenReturn(Optional.of(project));
        when(workflowRepo.findById(9L)).thenReturn(Optional.of(workflow));
        RenderConfigResolver resolver = new RenderConfigResolver(new ObjectMapper());
        skillEngine = mock(SkillEngine.class);
        preflightService = mock(PreflightService.class);
        ffmpeg = mock(FfmpegTool.class);
        materialStore = mock(MaterialStore.class);
        when(ffmpeg.ffmpegAvailable()).thenReturn(true);
        when(ffmpeg.ffprobeAvailable()).thenReturn(true);
        when(skillEngine.run(anyString(), any(), any(), anyInt(), isNull(), isNull()))
                .thenReturn(new MixPlanner.Plan());
        PreflightResult ready = new PreflightResult();
        ready.setStatus(PreflightResult.READY);
        when(preflightService.evaluate(any(), any(), eq(true), eq(true))).thenReturn(ready);
        service = new RenderAdmissionService(projectRepo, workflowRepo, new ObjectMapper(), resolver,
                skillEngine, preflightService, ffmpeg, mock(AudioContractService.class));
    }

    @Test
    void hashIsStableAndChangesWithInputs() {
        EffectiveRenderConfig first = service.resolve(9L, 7L, new MixParams());
        EffectiveRenderConfig second = service.resolve(9L, 7L, new MixParams());
        assertEquals(first.getConfigHash(), second.getConfigHash());
        MixParams changed = new MixParams(); changed.setMaterialIds(java.util.List.of(100L));
        assertNotEquals(first.getConfigHash(), service.resolve(9L, 7L, changed).getConfigHash());
        workflow.setVersion("2");
        assertNotEquals(first.getWorkflowHash(), service.resolve(9L, 7L, new MixParams()).getWorkflowHash());
    }

    @Test
    void materialFactsChangeHashWithoutExposingPath() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("admission-material", ".mp4");
        java.nio.file.Files.writeString(file, "one");
        Material material = new Material();
        material.setId(100L);
        material.setFilePath(file.toString());
        material.setDurationSec(3.0);
        when(materialStore.findAll()).thenReturn(java.util.List.of(material));
        RenderAdmissionService withStore = new RenderAdmissionService(projectRepo, workflowRepo, new ObjectMapper(),
                new RenderConfigResolver(new ObjectMapper()), skillEngine, preflightService, ffmpeg,
                mock(AudioContractService.class), materialStore);
        MixParams params = new MixParams();
        params.setMaterialIds(java.util.List.of(100L));
        EffectiveRenderConfig first = withStore.resolve(9L, 7L, params);
        java.nio.file.Files.writeString(file, "two-bytes");
        EffectiveRenderConfig second = withStore.resolve(9L, 7L, params);
        assertNotEquals(first.getMaterialScopeHash(), second.getMaterialScopeHash());
        assertNotEquals(first.getConfigHash(), second.getConfigHash());
        java.nio.file.Files.deleteIfExists(file);
    }

    @Test
    void expiredAndBlockedSnapshotsAreRejected() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams());
        AdmissionSnapshot expired = actual.getAdmission(); expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> service.verify(expired, actual));
        AdmissionSnapshot blocked = actual.getAdmission(); blocked.setExpiresAt(LocalDateTime.now().plusMinutes(1)); blocked.setStatus("blocked");
        assertThrows(IllegalArgumentException.class, () -> service.verify(blocked, actual));
    }

    @Test
    void stableSeedAndVariantPreparationArePartOfAdmission() {
        EffectiveRenderConfig first = service.resolve(9L, 7L, new MixParams(), 3, "prep-1");
        EffectiveRenderConfig second = service.resolve(9L, 7L, new MixParams(), 3, "prep-1");
        assertEquals(first.getParams().getSeed(), second.getParams().getSeed());
        assertEquals(first.getConfigHash(), second.getConfigHash());
        assertNotEquals(first.getConfigHash(), service.resolve(9L, 7L, new MixParams(), 4, "prep-1").getConfigHash());
        assertNotEquals(first.getConfigHash(), service.resolve(9L, 7L, new MixParams(), 3, "prep-2").getConfigHash());
    }

    @Test
    void blockedClientStatusChangedToReadyIsRejected() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams());
        AdmissionSnapshot supplied = actual.getAdmission();
        supplied.setStatus("ready");
        service.seal(supplied, "ready");
        PreflightResult blocked = new PreflightResult();
        blocked.setStatus(PreflightResult.BLOCKED);
        when(preflightService.evaluate(any(), any(), eq(true), eq(true))).thenReturn(blocked);
        assertThrows(IllegalArgumentException.class, () -> service.verify(supplied, actual));
    }

    @Test
    void currentConfigurationBlockerIsRejectedEvenWithMatchingSignature() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams());
        AdmissionSnapshot supplied = actual.getAdmission();
        service.seal(supplied, PreflightResult.READY);
        PreflightResult blocked = new PreflightResult();
        blocked.setStatus(PreflightResult.BLOCKED);
        when(preflightService.evaluate(any(), any(), eq(true), eq(true))).thenReturn(blocked);
        assertThrows(IllegalArgumentException.class, () -> service.verify(supplied, actual));
    }

    @Test
    void readyAndWarningStatusesPassServerRecomputation() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams());
        AdmissionSnapshot supplied = actual.getAdmission();
        service.seal(supplied, PreflightResult.READY);
        service.verify(supplied, actual);
        service.seal(supplied, PreflightResult.WARNING);
        PreflightResult warning = new PreflightResult();
        warning.setStatus(PreflightResult.WARNING);
        when(preflightService.evaluate(any(), any(), eq(true), eq(true))).thenReturn(warning);
        service.verify(supplied, actual);
    }

    @Test
    void statusTamperingAndMismatchesAreRejected() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams(), 2, "prep-1");
        AdmissionSnapshot tampered = actual.getAdmission();
        tampered.setStatus("warning");
        assertThrows(IllegalArgumentException.class, () -> service.verify(tampered, actual));
        EffectiveRenderConfig other = service.resolve(9L, 7L, new MixParams(), 2, "prep-2");
        assertThrows(IllegalArgumentException.class, () -> service.verify(actual.getAdmission(), other));
    }
}
