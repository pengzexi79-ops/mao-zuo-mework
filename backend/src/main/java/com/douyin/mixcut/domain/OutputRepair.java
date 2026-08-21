package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次 QC 问题判断与修复执行记录，供回溯、人工决策和前后版本比较。 */
@Data
@Entity
@Table(name = "output_repair", indexes = {
        @Index(name = "idx_output_repair_version", columnList = "output_version_id"),
        @Index(name = "idx_output_repair_job", columnList = "job_id,idx")
})
public class OutputRepair {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long outputVersionId;
    private Long jobOutputId;
    private Long jobId;
    @Column(name = "idx")
    private Integer idx;
    @Column(length = 32)
    private String category;
    @Column(length = 8)
    private String severity;
    @Column(length = 64)
    private String issueId;
    @Column(columnDefinition = "TEXT")
    private String evidence;
    private Boolean autoFixable = false;
    @Column(columnDefinition = "TEXT")
    private String aiAssessment;
    @Column(columnDefinition = "TEXT")
    private String recommendedAction;
    @Column(columnDefinition = "TEXT")
    private String candidateActions;
    @Column(length = 64)
    private String selectedAction;
    @Column(columnDefinition = "TEXT")
    private String executionImpact;
    @Column(length = 32)
    private String status = "proposed";
    @Column(columnDefinition = "TEXT")
    private String beforeQc;
    @Column(columnDefinition = "TEXT")
    private String afterQc;
    @Column(columnDefinition = "TEXT")
    private String error;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
