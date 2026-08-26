package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 每条成片的候选版本；失败版本只保留在受控 workspace，不作为可下载输出。 */
@Data
@Entity
@Table(name = "output_version", uniqueConstraints = {
        @UniqueConstraint(name = "uniq_output_version_job_idx_no", columnNames = {"job_id", "idx", "version_no"})
}, indexes = {
        @Index(name = "idx_output_version_output", columnList = "job_output_id"),
        @Index(name = "idx_output_version_job_idx", columnList = "job_id,idx")
})
public class OutputVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long jobOutputId;
    private Long jobId;
    @Column(name = "idx")
    private Integer idx;
    private Integer versionNo = 1;
    @Column(length = 32)
    private String status = "rendering";
    @Column(length = 1024)
    private String filePath;
    private Double durationSec;
    @Column(length = 1024)
    private String thumbnail;
    @Column(columnDefinition = "TEXT")
    private String planSnapshot;
    @Column(columnDefinition = "TEXT")
    private String paramsSnapshot;
    @Column(columnDefinition = "TEXT")
    private String usedMaterials;
    @Column(columnDefinition = "TEXT")
    private String repairStrategy;
    @Column(columnDefinition = "TEXT")
    private String qcJson;
    @Column(columnDefinition = "TEXT")
    private String qcReport;
    @Column(columnDefinition = "TEXT")
    private String error;
    private Integer parentVersionNo;
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
