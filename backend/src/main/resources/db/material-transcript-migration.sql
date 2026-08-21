-- Safe migration: add material_transcript table for persistent audited transcript data.
-- Run once on existing installations before starting the upgraded backend.

CREATE TABLE IF NOT EXISTS material_transcript (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    language    VARCHAR(32) NOT NULL DEFAULT 'zh',
    model       VARCHAR(128),
    cues        JSON,
    status      VARCHAR(32) NOT NULL DEFAULT 'pending',
    error       TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_material_transcript (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
