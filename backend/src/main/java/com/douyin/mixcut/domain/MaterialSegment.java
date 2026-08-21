package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材分析产出的镜头片段（可回放的时间区间，非磁盘文件）。
 *
 * <p>由 {@code MaterialAnalysisService} 通过 ffmpeg 场景检测或均匀切片兜底得到，
 * 供后续混剪规划复用，避免每次出片都重新跑一遍昂贵的场景检测。</p>
 */
@Data
@TableName("material_segment")
public class MaterialSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;

    /** 所属分析记录（可空，便于追溯与按分析清理）。 */
    private Long analysisId;

    /** 片段顺序（从 0 开始）。 */
    private Integer idx;

    private Double startSec;

    private Double endSec;

    private Double durationSec;

    /** 场景变化分数（场景检测时有效；兜底切片为 null）。 */
    private Double score;

    /** 片段代表帧在源素材中的时间点。 */
    private Double representativeFrameAtSec;

    /** 片段代表帧的受控文件 URL；文件位于应用 cache/thumbs 下。 */
    private String representativeFrameUrl;

    private LocalDateTime createdAt;
}
