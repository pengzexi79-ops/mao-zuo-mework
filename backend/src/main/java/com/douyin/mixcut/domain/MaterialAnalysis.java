package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材结构化分析结果（每个素材保留最新一条）。
 *
 * <p>分析内容由异步 {@code MaterialAnalysisService} 生成，包含镜头切分来源
 * （scene=ffmpeg 场景检测 / fallback=均匀切片兜底）、OCR 文字、结构化标签、
 * 人话摘要与问题提示。segment 明细落在 {@link MaterialSegment}，本表只保存汇总。</p>
 */
@Data
@TableName("material_analysis")
public class MaterialAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;

    /** pending / running / completed / failed */
    private String status = "pending";

    /** 镜头切分来源：scene=ffmpeg 场景检测；fallback=均匀切片兜底。 */
    private String source;

    /** 结构化标签 JSON 数组，例如 ["美妆","带货"]；AI 失败时回退到文件名/OCR 推导标签。 */
    private String tagsJson;

    /** OCR 识别文字 JSON 数组。 */
    private String ocrTextsJson;

    /** 转写缓存状态：pending / running / completed / failed，或 null 表示从未转写。 */
    private String transcriptStatus;

    /** 人话摘要。 */
    private String summary;

    /** 问题/提示 JSON 数组。 */
    private String issuesJson;

    /** 固定间隔采样帧 JSON 数组，元素包含 atSec 与 url。 */
    private String sampleFramesJson;

    /** 失败原因（status=failed 时）；完成态必须能持久化清空旧错误。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String error;

    /** 源文件内容指纹；文件变化后旧分析不会被复用。 */
    private String sourceFingerprint;

    /** 分析契约版本；算法升级后可强制淘汰旧结果。 */
    private String indexVersion;

    /** 同一素材的分析尝试次数，便于追踪重建和失败恢复。 */
    private Integer attemptCount = 0;

    /** 最近一次成功完成索引的时间。 */
    private LocalDateTime indexedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
