-- 幂等迁移：新增素材结构化分析与镜头片段持久化表。
-- 已存在安装可重复执行；新安装也会由 BootstrapService 在启动时自动创建。

CREATE TABLE IF NOT EXISTS material_analysis (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id      BIGINT NOT NULL,
    status           VARCHAR(32) NOT NULL DEFAULT 'pending',
    source           VARCHAR(32),
    tags_json        JSON,
    ocr_texts_json   JSON,
    transcript_status VARCHAR(32),
    summary          TEXT,
    issues_json      JSON,
    error            TEXT,
    source_fingerprint VARCHAR(128),
    index_version    VARCHAR(64),
    attempt_count    INT NOT NULL DEFAULT 0,
    indexed_at       DATETIME NULL,
    sample_frames_json JSON,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_material_analysis (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS material_segment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id  BIGINT NOT NULL,
    analysis_id  BIGINT,
    idx          INT NOT NULL DEFAULT 0,
    start_sec    DOUBLE,
    end_sec      DOUBLE,
    duration_sec DOUBLE,
    score        DOUBLE,
    representative_frame_at_sec DOUBLE,
    representative_frame_url VARCHAR(1024),
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_material_segment_material (material_id),
    INDEX idx_material_segment_analysis (analysis_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
