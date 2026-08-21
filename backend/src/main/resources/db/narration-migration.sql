-- Safe migration: add task-level AI narration caption and editorial brief tables.
-- Idempotent; run once on existing installations before starting the upgraded backend.

CREATE TABLE IF NOT EXISTS narration_caption (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id            BIGINT,
    idx               INT NOT NULL DEFAULT 0,
    voice_material_id BIGINT,
    script_text       TEXT,
    cues              JSON,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    error             TEXT,
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_narration_caption_job (job_id),
    UNIQUE KEY uniq_narration_caption (job_id, idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS editorial_brief (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id             BIGINT,
    project_id         BIGINT,
    mood_keywords      JSON,
    prefer_human_voice TINYINT(1) NOT NULL DEFAULT 1,
    duck_bgm           TINYINT(1) NOT NULL DEFAULT 1,
    bgm_material_id    BIGINT,
    summary            TEXT,
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_editorial_brief_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
