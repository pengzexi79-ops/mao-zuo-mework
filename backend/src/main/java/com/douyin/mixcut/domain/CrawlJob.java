package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 后台采集任务；一个任务包含多个可恢复的 CrawlTask。 */
@Data
@Entity
@Table(name = "crawl_job")
public class CrawlJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(length = 32) private String mode = "video";
    @Column(length = 32) private String role = "body";
    @Column(length = 32) private String status = JobStatus.pending.name();
    private Integer progress = 0;
    @Column(name = "current_item") private Integer currentItem = 0;
    private Integer total = 0;
    @Column(columnDefinition = "json") private String params;
    @Column(columnDefinition = "text") private String summary;
    @Column(columnDefinition = "text") private String error;
    private Integer timeoutSec = 0;
    private Integer staleAfterSec = 0;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); if (lastActivityAt == null) lastActivityAt = createdAt; updatedAt = LocalDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
