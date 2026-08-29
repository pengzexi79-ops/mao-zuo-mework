package com.douyin.mixcut.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Prevents stale entity saves from overwriting a newer user or recovery transition. */
    @Version
    private Long version = 0L;

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
    /** Persistent execution fencing generation; it changes whenever a worker is claimed or invalidated. */
    private Long executionEpoch = 0L;
    @JsonIgnore
    @Column(length = 64)
    private String leaseToken;
    @JsonIgnore
    private LocalDateTime leaseExpiresAt;
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
        if (params == null || params.isBlank()) return false;
        try {
            JsonNode root = PARAMS_MAPPER.readTree(params);
            // New snapshots keep the scheduler flag at the root. Older snapshots placed it
            // inside effectiveParams, so retain that format for resumable historical jobs.
            if (root.has("continuous")) return root.path("continuous").asBoolean(false);
            return root.path("effectiveParams").path("continuous").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Exposes the persisted per-task failure policy to the task list without exposing a key. */
    @Transient
    public boolean isForceContinue() {
        if (params == null || params.isBlank()) return false;
        try {
            JsonNode root = PARAMS_MAPPER.readTree(params);
            if (root.has("forceContinue")) return root.path("forceContinue").asBoolean(false);
            return root.path("effectiveParams").path("forceContinue").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActivityAt == null) lastActivityAt = createdAt;
        if (version == null) version = 0L;
        if (executionEpoch == null) executionEpoch = 0L;
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
