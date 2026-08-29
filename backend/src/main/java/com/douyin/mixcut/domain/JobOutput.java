package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 出片结果：一个 Job 对应 N 条成片。 */
@Data
@Entity
@Table(name = "job_output")
public class JobOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    /** 第几条（从 1 开始） */
    @Column(name = "idx")
    private Integer idx = 0;

    private String filePath;
    private Double durationSec;
    private String thumbnail;

    /** Delivery gate result: pass, warn, or fail. */
    private String qcStatus;
    @Column(name = "qc_report", columnDefinition = "TEXT")
    private String qcReport;

    /** Structured six-dimension delivery QC report (JSON), shown per-category in the outputs library. */
    @Column(name = "qc_json", columnDefinition = "TEXT")
    private String qcJson;

    /** Automatic retry count for this output (0 = first attempt succeeded; capped at 2). */
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** Hook strategy actually used for this output. */
    @Column(name = "hook_strategy", length = 32)
    private String hookStrategy;

    /** Explainable downgrade/fallback notes (JSON array of strings). */
    @Column(name = "downgrade_info", columnDefinition = "TEXT")
    private String downgradeInfo;

    /** Used material timeline (JSON array of {materialId,name,slot,kind,start,duration}). */
    @Column(name = "used_materials", columnDefinition = "TEXT")
    private String usedMaterials;

    /** Stable source-slice keys, persisted for pause/resume and process-restart deduplication. */
    @Column(name = "segment_keys", columnDefinition = "TEXT")
    private String segmentKeys;

    /** True when the scheduler counted this failed item as processed after explicit force-continue. */
    @Column(name = "forced_continue")
    private Boolean forcedContinue = false;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
