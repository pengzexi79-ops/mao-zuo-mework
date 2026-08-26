package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.EditorialBrief;
import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.repository.EditorialBriefStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditorialBriefServiceTest {

    @Mock private EditorialBriefStore store;

    @Test
    void deriveIntentMapsToneAndCategoryToMoodKeywords() {
        Project project = new Project();
        project.setTone("轻松种草");
        project.setCategory("美妆");

        MixPlanner.AudioIntent intent = EditorialBriefService.deriveIntent(project);

        assertTrue(intent.isPresent());
        assertTrue(intent.getMoodKeywords().contains("轻快"));
        assertTrue(intent.getMoodKeywords().contains("美妆"));
        assertTrue(intent.isPreferHumanVoice());
        assertTrue(intent.isDuckBgm());
    }

    @Test
    void deriveIntentIsEmptyForNullProject() {
        MixPlanner.AudioIntent intent = EditorialBriefService.deriveIntent(null);

        assertFalse(intent.isPresent());
        assertTrue(intent.getMoodKeywords().isEmpty());
    }

    @Test
    void deriveHookStrategyIsDeterministicFromProjectSemantics() {
        Project project = new Project();
        project.setTone("真实测评");

        assertEquals("RESULT", EditorialBriefService.deriveHookStrategy(project));
        assertEquals("COUNTERINTUITIVE", EditorialBriefService.deriveHookStrategy(null));
    }

    @Test
    void persistForJobIsIdempotent() {
        Project project = new Project();
        project.setId(5L);
        project.setTone("轻松");
        when(store.findByJobId(10L)).thenReturn(Optional.empty(), Optional.of(existingBrief()));
        when(store.save(any(EditorialBrief.class))).thenAnswer(inv -> inv.getArgument(0));

        EditorialBriefService service = new EditorialBriefService(store);
        EditorialBrief first = service.persistForJob(10L, project);
        EditorialBrief second = service.persistForJob(10L, project);

        assertNotNull(first);
        assertEquals(10L, first.getJobId());
        assertEquals(99L, second.getId(), "replay should return the existing record");
        verify(store, times(1)).save(any(EditorialBrief.class));
    }

    private EditorialBrief existingBrief() {
        EditorialBrief brief = new EditorialBrief();
        brief.setId(99L);
        brief.setJobId(10L);
        return brief;
    }
}
