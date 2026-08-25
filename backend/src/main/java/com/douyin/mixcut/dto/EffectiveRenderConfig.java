package com.douyin.mixcut.dto;

import com.douyin.mixcut.domain.Project;
import com.douyin.mixcut.domain.Workflow;
import lombok.Data;

/** Resolved render inputs used by both dry-run and submit admission. */
@Data
public class EffectiveRenderConfig {
    private MixParams params;
    private Project project;
    private Workflow workflow;
    private String workflowDef;
    private String workflowHash;
    private String materialScopeHash;
    private String configHash;
    private Integer variant = 0;
    private String preparationId;
    private AdmissionSnapshot admission;
}
