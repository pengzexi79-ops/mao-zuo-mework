package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目：绑定品牌/品类/产品/卖点/受众/语气/禁用词。
 * 生成时自动作为系统上下文注入给 AI，并可对每种用途单独覆盖路由。
 */
@Data
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String brand;
    private String category;
    private String product;

    @Column(columnDefinition = "text")
    private String sellingPoints;

    private String audience;
    private String tone;

    @Column(columnDefinition = "text")
    private String bannedWords;

    @Column(columnDefinition = "text")
    private String extraPrompt;

    @Column(columnDefinition = "json")
    private String defaultParams;

    @Column(columnDefinition = "json")
    private String routeOverrides;

    @Column(name = "is_builtin")
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
