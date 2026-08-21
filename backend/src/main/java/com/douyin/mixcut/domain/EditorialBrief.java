package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Task-level editorial brief persisted for audit and reuse.
 *
 * <p>Captures the content/project intent that drives audio selection: preferred BGM mood
 * keywords, whether a human voice should take priority, and whether BGM should be ducked
 * under that voice. Derived deterministically from {@link Project} semantics, so it works
 * offline and always preserves a fallback when no BGM matches.</p>
 */
@Data
@TableName("editorial_brief")
public class EditorialBrief {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private Long projectId;

    /** JSON array of preferred BGM mood keywords, e.g. ["轻快","upbeat"]. */
    private String moodKeywords;

    /** 任务级钩子策略（从项目语义推导），供批量出片每条按 variant 轮换。 */
    private String hookStrategy;

    private Boolean preferHumanVoice = true;

    private Boolean duckBgm = true;

    /** The BGM material ultimately selected for this task (audit trail). */
    private Long bgmMaterialId;

    private String summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
