package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 采集任务中的单条 URL/远程音频检查点。 */
@Data
@Entity
@Table(name = "crawl_task", uniqueConstraints = @UniqueConstraint(name = "uniq_crawl_task_idx", columnNames = {"job_id", "idx"}))
public class CrawlTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "job_id", nullable = false) private Long jobId;
    @Column(name = "idx", nullable = false) private Integer idx;
    @Column(length = 1024) private String url;
    @Column(length = 512) private String title;
    @Column(length = 32) private String status = JobStatus.pending.name();
    @Column(length = 32) private String via;
    @Column(name = "error_code", length = 48) private String errorCode;
    @Column(name = "source", length = 64) private String source;
    @Column(name = "http_status") private Integer httpStatus;
    @Column(columnDefinition = "text") private String message;
    @Column(name = "material_id") private Long materialId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** Derived at read time for URL guard rejections; never persisted or user-controlled. */
    @Transient private boolean guardRejected;
    @Transient private String downloadStatus;
    @Transient private String admissionStatus;
    @Transient private String admissionReason;
    @Transient private boolean fileExists;
    @Transient private boolean usable;
}
