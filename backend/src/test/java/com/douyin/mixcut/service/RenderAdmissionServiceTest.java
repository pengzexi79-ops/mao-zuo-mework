package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.Workflow;
import com.douyin.mixcut.dto.AdmissionSnapshot;
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
        service = new RenderAdmissionService(projectRepo, workflowRepo, new ObjectMapper(), resolver, mock(SkillEngine.class));
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
    void statusTamperingAndMismatchesAreRejected() {
        EffectiveRenderConfig actual = service.resolve(9L, 7L, new MixParams(), 2, "prep-1");
        AdmissionSnapshot tampered = actual.getAdmission();
        tampered.setStatus("warning");
        assertThrows(IllegalArgumentException.class, () -> service.verify(tampered, actual));
        EffectiveRenderConfig other = service.resolve(9L, 7L, new MixParams(), 2, "prep-2");
        assertThrows(IllegalArgumentException.class, () -> service.verify(actual.getAdmission(), other));
    }
}
