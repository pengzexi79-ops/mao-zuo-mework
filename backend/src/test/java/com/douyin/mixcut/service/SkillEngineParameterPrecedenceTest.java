package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.dto.MixParams;
import com.douyin.mixcut.repository.MaterialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillEngineParameterPrecedenceTest {

    @Mock private MaterialStore materialStore;

    @Test
    void submittedFormParametersOverrideWorkflowSuggestions() {
        MixPlanner planner = spy(new MixPlanner());
        ArgumentCaptor<MixParams> captured = ArgumentCaptor.forClass(MixParams.class);
        doAnswer(invocation -> new MixPlanner.Plan()).when(planner).plan(
                any(MixPlanner.Pool.class), captured.capture(), anyInt(), any(), any());

        SkillEngine engine = new SkillEngine(materialStore, null, null, null, null, null, planner, null);
        Material visual = new Material();
        visual.setId(1L);
        visual.setFilePath("managed/sample.jpg");
        visual.setFileType(Material.FileType.image);
        visual.setStatus(Material.Status.ready);
        when(materialStore.findAll()).thenReturn(List.of(visual));

        MixParams submitted = new MixParams();
        submitted.setMinSec(101);
        submitted.setMaxSec(102);
        submitted.setSliceSec(5.0);
        submitted.setAiHook(false);

        engine.run("""
                {"steps":[
                  {"skill":"set_duration","args":{"minSec":60,"maxSec":60,"dense":true}},
                  {"skill":"set_slice","args":{"sliceSec":2,"jitter":0.2}}
                ]}
                """, null, submitted, 0, null, null);

        MixParams actual = captured.getValue();
        assertEquals(101, actual.getMinSec());
        assertEquals(102, actual.getMaxSec());
        assertEquals(5.0, actual.getSliceSec(), 0.001);
    }
}
