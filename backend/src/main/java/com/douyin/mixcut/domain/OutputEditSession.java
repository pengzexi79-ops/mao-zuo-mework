package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** User-authored edit draft for one output. Candidate renders never replace delivery automatically. */
@Data
@Entity
@Table(name = "output_edit_session", indexes = {
        @Index(name = "idx_output_edit_session_output", columnList = "job_id,idx"),
        @Index(name = "idx_output_edit_session_status", columnList = "status")
})
public class OutputEditSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long jobId;
    @Column(name = "idx")
    private Integer idx;
    private Long baseVersionId;
    private Long candidateVersionId;
    @Column(length = 32)
    private String status = "draft";
    @Column(columnDefinition = "TEXT")
    private String planSnapshot;
    @Column(columnDefinition = "TEXT")
    private String paramsSnapshot;
    @Column(columnDefinition = "TEXT")
    private String comment;
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
