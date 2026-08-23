-- P3-2 isolated acceptance schema.
-- The database name is intentionally fixed and must be checked before execution.
CREATE TABLE IF NOT EXISTS material_folder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    parent_id BIGINT,
    path VARCHAR(1024),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_acceptance_folder_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    folder_id BIGINT,
    name VARCHAR(512) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    file_type ENUM('video','audio','image') NOT NULL DEFAULT 'video',
    role VARCHAR(32) NOT NULL DEFAULT 'none',
    duration_sec DOUBLE,
    width INT,
    height INT,
    thumbnail VARCHAR(1024),
    tags VARCHAR(512),
    source ENUM('local','crawl','generated') NOT NULL DEFAULT 'local',
    status ENUM('ready','processing','failed') NOT NULL DEFAULT 'ready',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_acceptance_material_folder (folder_id),
    INDEX idx_acceptance_material_path (file_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS material_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    source VARCHAR(32),
    tags_json JSON,
    ocr_texts_json JSON,
    transcript_status VARCHAR(32),
    summary TEXT,
    issues_json JSON,
    source_fingerprint VARCHAR(128),
    index_version VARCHAR(64),
    attempt_count INT NOT NULL DEFAULT 0,
    indexed_at DATETIME NULL,
    sample_frames_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_acceptance_analysis_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS media_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key VARCHAR(64) NOT NULL UNIQUE,
    kind VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress INT NOT NULL DEFAULT 0,
    engine VARCHAR(128),
    params JSON,
    message TEXT,
    error TEXT,
    output_directory TEXT,
    result_paths JSON,
    results JSON,
    retry_count INT NOT NULL DEFAULT 0,
    timeout_sec INT NOT NULL DEFAULT 1800,
    stale_after_sec INT NOT NULL DEFAULT 900,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_acceptance_media_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS media_generation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key VARCHAR(64) NOT NULL UNIQUE,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'accepted',
    phase VARCHAR(32),
    progress INT NOT NULL DEFAULT 0,
    provider_id BIGINT,
    provider VARCHAR(128),
    model VARCHAR(128),
    input_snapshot JSON,
    remote_task_id VARCHAR(255),
    material_id BIGINT,
    staging_file_path TEXT,
    idempotency_key VARCHAR(64),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 2,
    next_attempt_at DATETIME,
    error_code VARCHAR(64),
    error TEXT,
    message TEXT,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_acceptance_generation_status (status),
    INDEX idx_acceptance_generation_phase (phase),
    INDEX idx_acceptance_generation_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress INT NOT NULL DEFAULT 0,
    params JSON,
    current INT NOT NULL DEFAULT 0,
    total INT NOT NULL DEFAULT 0,
    timeout_sec INT NOT NULL DEFAULT 0,
    stale_after_sec INT NOT NULL DEFAULT 0,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    current_step VARCHAR(512),
    phase_progress INT NOT NULL DEFAULT 0,
    summary TEXT,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_acceptance_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS output_qc (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT,
    output_path VARCHAR(1024),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    report JSON,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_acceptance_qc_job (job_id),
    INDEX idx_acceptance_qc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
