package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Task-level AI narration caption data.
 *
 * <p>The cues here are produced by ASR against the <em>actually generated</em> narration
 * audio (synthesize first, then transcribe), never by guessing script timing. They are the
 * authority used by the render path to burn AI voice subtitles. Keyed by (jobId, idx) so each
 * output keeps its own captions and restarts can reuse the checkpoint.</p>
 */
@Data
@TableName("narration_caption")
public class NarrationCaption {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Owning render task; nullable only for ad-hoc/dry-run generation. */
    private Long jobId;

    /** Output index within the job (from 1). */
    private Integer idx = 0;

    /** The generated narration voice material that was transcribed. */
    private Long voiceMaterialId;

    /** The narration script text that was synthesized. */
    private String scriptText;

    /** JSON array of ASR cues: [{"start":0.0,"end":2.5,"text":"..."}]. */
    private String cues;

    /** completed=has real cues; no_cues=voice generated but ASR returned no timeline; failed=ASR crashed. */
    private String status = "pending";

    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
