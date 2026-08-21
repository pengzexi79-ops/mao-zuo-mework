package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 素材文件夹（对应本地一个目录，可批量扫描）。 */
@Data
@Entity
@Table(name = "material_folder")
public class MaterialFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** 总包素材库使用父子层级；旧的手动文件夹保持 null。 */
    @Column(name = "parent_id")
    private Long parentId;

    private String path;
    @Column(columnDefinition = "text")
    private String description;
    private Boolean enabled = true;
    private Integer sortOrder = 0;
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
