package com.douyin.mixcut.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent audited transcript/caption data for a material.
 * Stores language, model, timestamps, cues, status, and error info.
 * Allows caching/reuse of transcription results across diagnosis and render runs.
 */
@Data
@TableName("material_transcript")
public class MaterialTranscript {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long materialId;

    /** Language code, e.g. zh, en. Default zh. */
    private String language = "zh";

    /** ASR/transcription model used, e.g. whisper, vosk. */
    private String model;

    /** JSON array of timestamp entries: [{"start":0.0,"end":2.5,"text":"hello"}] */
    private String cues;

    /** Status: pending, running, completed, failed */
    private String status = "pending";

    /** Error message if status is failed */
    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
