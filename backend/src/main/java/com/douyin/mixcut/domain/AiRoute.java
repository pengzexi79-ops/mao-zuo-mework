package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 用途路由：每个用途（钩子/脚本/标题/引导/标签/通用）绑定供应商 + 模型 + 兜底链。 */
@Data
@Entity
@Table(name = "ai_route")
public class AiRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String useCase;
    private Long providerId;
    private String model;

    @Column(columnDefinition = "json")
    private String fallbacks;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
