package com.douyin.mixcut.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Skill（能力单元）定义。
 *
 * 三种类型：
 * - builtin：系统内置能力（取素材 / 拆条 / 混剪 / 生成钩子 / 配乐 / 字幕…）
 * - ai/script：用户自定义的受约束 JSON DSL；两者只能使用安全 op 操作素材池、MixParams 和文案
 *
 * 自定义 def 绝不是命令、ffmpeg 模板或 HTTP 模板；保存与执行前均由 SkillEngine 严格验证。
 */
@Data
@Entity
@Table(name = "skill_def")
public class SkillDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 唯一名称，AI function-calling 时用这个名字 */
    private String name;

    /** 给 AI 看的自然语言说明，写清楚"什么时候该用它" */
    @Column(columnDefinition = "text")
    private String description;

    /** builtin / ai / script */
    @Column(name = "type", length = 32)
    private String type = SkillType.builtin.name();

    /** JSON：参数 schema + 默认值 + 脚本模板 */
    @Column(columnDefinition = "json")
    private String def;

    private Boolean enabled = true;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

}
