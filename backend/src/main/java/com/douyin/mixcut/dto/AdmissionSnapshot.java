package com.douyin.mixcut.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable-at-submission admission facts carried by dry-run and submit. */
@Data
public class AdmissionSnapshot {
    private String configHash;
    private String workflowHash;
    private String materialScopeHash;
    private LocalDateTime checkedAt;
    private LocalDateTime expiresAt;
    private String preparationId;
    private Integer variant = 0;
    private String status;
    /** Canonical, public admission signature; no server secret is required. */
    private String statusSignature;
    private Map<String, Object> runtimeSnapshot = new LinkedHashMap<>();
}
