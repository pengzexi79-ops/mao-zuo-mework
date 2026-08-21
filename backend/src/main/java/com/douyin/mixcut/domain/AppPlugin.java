package com.douyin.mixcut.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户可注册的应用插件元数据。仅保存入口、说明和 manifest，不执行远程代码。 */
@Data
@Entity
@Table(name = "app_plugin")
public class AppPlugin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_key", nullable = false, unique = true, length = 128)
    private String key;

    private String name;

    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "entry_url", columnDefinition = "TEXT")
    private String entryUrl;

    private Integer priority = 100;

    private Boolean enabled = true;

    @Column(columnDefinition = "json")
    private String manifest;

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
