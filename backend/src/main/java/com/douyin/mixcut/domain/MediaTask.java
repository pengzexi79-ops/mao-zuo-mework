package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Persisted lifecycle record for local media-tool operations. */
@Data
@Entity
@Table(name = "media_task", indexes = {
        @Index(name = "idx_media_task_key", columnList = "task_key", unique = true),
        @Index(name = "idx_media_task_status", columnList = "status")
})
public class MediaTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_key", length = 64, nullable = false, unique = true)
    private String taskKey;
    @Column(length = 64, nullable = false)
    private String kind;
    @Column(length = 32, nullable = false)
    private String status = "pending";
    private Integer progress = 0;
    @Column(length = 128)
    private String engine;
    @Column(columnDefinition = "json")
    private String params;
    @Column(columnDefinition = "text")
    private String message;
    @Column(columnDefinition = "text")
    private String error;
    @Column(columnDefinition = "text")
    private String outputDirectory;
    @Column(name = "result_paths", columnDefinition = "json")
    private String resultPaths;
    @Column(columnDefinition = "json")
    private String results;
    private Integer retryCount = 0;
    private Integer timeoutSec = 1800;
    private Integer staleAfterSec = 900;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
