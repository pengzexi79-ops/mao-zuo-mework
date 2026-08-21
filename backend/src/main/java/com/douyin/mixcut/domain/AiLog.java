package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** AI 调用流水：用于排障、成本核算、供应商可用性统计。 */
@Data
@Entity
@Table(name = "ai_log")
public class AiLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long providerId;

    @Column(length = 32)
    private String useCase;

    private String model;

    private Boolean ok = true;

    private Integer latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal cost;

    /** 返回内容前 500 字，便于回看 */
    @Column(columnDefinition = "text")
    private String preview;

    @Column(columnDefinition = "text")
    private String error;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
