package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** AI 供应商（一个可接入的 API 端点：OpenAI 兼容 / Anthropic / Gemini）。 */
@Data
@Entity
@Table(name = "ai_provider")
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private ProviderKind kind;

    private String baseUrl;
    private String apiKey;
    private Integer priority = 5;
    private Boolean enabled = true;

    @Column(columnDefinition = "json")
    private String models;

    /** Request-only media capability document; persisted inside the existing models JSON for old database compatibility. */
    @Transient
    private String mediaCapabilities;

    private String defaultModel;
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
