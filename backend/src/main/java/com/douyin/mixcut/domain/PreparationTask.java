package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 出片准备任务（异步工作流）。
 *
 * <p>POST /api/jobs/prepare 只负责持久化一条 running 任务并交给 prepareExecutor 后台执行，
 * HTTP 请求线程绝不等待抓取队列；前端通过 GET /api/jobs/prepare/{id} 轮询阶段、缺口、
 * 自动补齐结果与耗时状态。JSON 列保存 MixParams / stages / MaterialGapResult / AutoFillResult 快照。</p>
 */
@Data
@Entity
@Table(name = "preparation_task")
public class PreparationTask {
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_TIMEDOUT = "timedout";
    public static final String STATUS_FAILED = "failed";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 32) private String status = STATUS_RUNNING;
    private Long projectId;
    @Column(columnDefinition = "json") private String params;
    private Boolean useAi = true;
    private Integer waitSeconds = 45;
    @Column(length = 255) private String keyword;
    private Boolean aiUsed = false;
    private Boolean ready = false;
    private Boolean timedOut = false;
    private Integer waitedSeconds = 0;
    @Column(columnDefinition = "json") private String crawlJobIds;
    @Column(columnDefinition = "json") private String stages;
    @Column(columnDefinition = "json") private String initialGap;
    @Column(columnDefinition = "json") private String finalGap;
    @Column(columnDefinition = "json") private String autoFill;
    @Column(columnDefinition = "text") private String error;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); if (lastActivityAt == null) lastActivityAt = createdAt; updatedAt = LocalDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
