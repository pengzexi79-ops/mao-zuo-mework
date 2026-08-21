package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 批量出片任务。一个 Job = 一次"生成 N 条视频"的请求。
 */
@Data
@Entity
@Table(name = "job")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workflowId;
    private Long projectId;
    private String name;

    /** 本次要出多少条 */
    @Column(name = "`count`")
    private Integer count = 1;

    /** pending / running / done / failed / cancelled */
    @Column(length = 32)
    private String status = JobStatus.pending.name();

    /** 0-100 */
    private Integer progress = 0;

    /** JSON：本次运行的参数快照（覆盖工作流默认值） */
    @Column(columnDefinition = "json")
    private String params;

    /** 已完成条数 */
    @Column(name = "`current`")
    private Integer current = 0;

    /** 总条数（= count） */
    private Integer total = 0;

    /** 可选的任务总超时与僵死检测时间（秒），0 表示使用应用默认值。 */
    private Integer timeoutSec = 0;
    private Integer staleAfterSec = 0;
    private LocalDateTime lastActivityAt;
    @Column(length = 512)
    private String currentStep;
    private Integer phaseProgress = 0;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String error;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 连续出片标记保存在 params 快照中，避免为已有本机库强制迁移字段。 */
    @Transient
    public boolean isContinuous() {
        return params != null && params.replaceAll("\\s", "").contains("\"continuous\":true");
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActivityAt == null) lastActivityAt = createdAt;
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
