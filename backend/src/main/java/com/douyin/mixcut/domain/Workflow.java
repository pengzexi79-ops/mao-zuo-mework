package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工作流定义（可导入/导出/协作复用）。
 * def 为 JSON：包含节点（素材选择 / 拆条策略 / 钩子来源 / 时长 / 配音 / BGM / 字幕 等）。
 */
@Data
@Entity
@Table(name = "workflow")
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String version;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "json")
    private String def;

    private Boolean isBuiltin = false;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
