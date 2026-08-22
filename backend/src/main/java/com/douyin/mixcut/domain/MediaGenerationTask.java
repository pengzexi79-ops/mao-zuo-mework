package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Persisted task state for paid/provider-backed media generation. */
@Data
@Entity
@Table(name = "media_generation_task", indexes = {
        @Index(name = "idx_media_generation_task_key", columnList = "task_key", unique = true),
        @Index(name = "idx_media_generation_task_status", columnList = "status"),
        @Index(name = "idx_media_generation_task_idempotency", columnList = "idempotency_key")
})
public class MediaGenerationTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_key", length = 64, nullable = false, unique = true)
    private String taskKey;
    @Column(length = 32, nullable = false)
    private String kind;
    @Column(length = 32, nullable = false)
    private String status = "accepted";
    @Column(length = 32)
    private String phase;
    private Integer progress = 0;
    private Long providerId;
    @Column(length = 128)
    private String provider;
    @Column(length = 128)
    private String model;
    @Column(columnDefinition = "json")
    private String inputSnapshot;
    @Column(length = 255)
    private String remoteTaskId;
    private Long materialId;
    @Column(columnDefinition = "text")
    private String stagingFilePath;
    @Column(length = 64)
    private String idempotencyKey;
    private Integer attemptCount = 0;
    private Integer maxAttempts = 2;
    private LocalDateTime nextAttemptAt;
    @Column(length = 64)
    private String errorCode;
    @Column(columnDefinition = "text")
    private String error;
    @Column(columnDefinition = "text")
    private String message;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastActivityAt == null) lastActivityAt = createdAt;
        updatedAt = LocalDateTime.now();
    }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }
}
