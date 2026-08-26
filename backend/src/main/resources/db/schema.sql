-- 喵作 · Mework - MySQL 8 初始化脚本
-- 用途：在 MySQL 8 中执行本文件创建库与全部表（幂等，可重复执行）
CREATE DATABASE IF NOT EXISTS ai_mix_video DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_mix_video;

CREATE TABLE IF NOT EXISTS material_folder (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    parent_id   BIGINT,
    path        VARCHAR(1024),
    description TEXT,
    enabled     TINYINT(1) NOT NULL DEFAULT 1,
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_material_folder_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS material (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    folder_id   BIGINT,
    name        VARCHAR(512) NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,
    file_type   ENUM('video','audio','image') NOT NULL DEFAULT 'video',
    role        VARCHAR(32) NOT NULL DEFAULT 'none',
    duration_sec DOUBLE,
    width       INT,
    height      INT,
    thumbnail   VARCHAR(1024),
    tags        VARCHAR(512),
    source      ENUM('local','crawl','generated') NOT NULL DEFAULT 'local',
    source_url  VARCHAR(1024),
    status      ENUM('ready','processing','failed') NOT NULL DEFAULT 'ready',
    mute_original_audio TINYINT(1) NOT NULL DEFAULT 0,
    transcribe_for_subtitles TINYINT(1) NOT NULL DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_role (role),
    INDEX idx_folder (folder_id),
    -- utf8mb4 VARCHAR(1024) 全列索引会超过 InnoDB 3072-byte 限制，使用前缀索引。
    INDEX idx_file_path (file_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_provider (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    kind         VARCHAR(32) NOT NULL,
    base_url     VARCHAR(512),
    api_key      VARCHAR(1024),
    priority     INT NOT NULL DEFAULT 5,
    enabled      TINYINT(1) NOT NULL DEFAULT 1,
    models       JSON,
    default_model VARCHAR(255),
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_route (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    use_case    VARCHAR(32) NOT NULL,
    provider_id BIGINT,
    model       VARCHAR(255),
    fallbacks   JSON,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_use_case (use_case)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id  BIGINT,
    use_case     VARCHAR(32),
    model        VARCHAR(255),
    ok           TINYINT(1) NOT NULL DEFAULT 1,
    latency_ms   INT,
    prompt_tokens INT,
    completion_tokens INT,
    cost         DECIMAL(12,6),
    preview      TEXT,
    error        TEXT,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    brand           VARCHAR(255),
    category        VARCHAR(128),
    product         VARCHAR(512),
    selling_points  TEXT,
    audience        VARCHAR(255),
    tone            VARCHAR(128),
    banned_words    TEXT,
    extra_prompt    TEXT,
    default_params  JSON,
    route_overrides JSON,
    is_builtin      TINYINT(1) NOT NULL DEFAULT 0,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    version     VARCHAR(32),
    description TEXT,
    def         JSON NOT NULL,
    is_builtin  TINYINT(1) NOT NULL DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_def (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    type        VARCHAR(32) NOT NULL DEFAULT 'builtin',
    def         JSON,
    enabled     TINYINT(1) NOT NULL DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS job (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version     BIGINT NOT NULL DEFAULT 0,
    workflow_id BIGINT,
    project_id  BIGINT,
    name        VARCHAR(255),
    `count`     INT NOT NULL DEFAULT 1,
    status      VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress    INT NOT NULL DEFAULT 0,
    params      JSON,
    current     INT NOT NULL DEFAULT 0,
    total       INT NOT NULL DEFAULT 0,
    timeout_sec INT NOT NULL DEFAULT 0,
    stale_after_sec INT NOT NULL DEFAULT 0,
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    lease_token VARCHAR(64),
    lease_expires_at DATETIME NULL,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    current_step VARCHAR(512),
    phase_progress INT NOT NULL DEFAULT 0,
    summary     TEXT,
    error       TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawl_job (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(255),
    mode             VARCHAR(32) NOT NULL DEFAULT 'video',
    role             VARCHAR(32) NOT NULL DEFAULT 'body',
    status           VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress         INT NOT NULL DEFAULT 0,
    current_item     INT NOT NULL DEFAULT 0,
    total            INT NOT NULL DEFAULT 0,
    params           JSON,
    summary          TEXT,
    error            TEXT,
    timeout_sec      INT NOT NULL DEFAULT 0,
    stale_after_sec  INT NOT NULL DEFAULT 0,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_crawl_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crawl_task (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id       BIGINT NOT NULL,
    idx          INT NOT NULL,
    url          VARCHAR(1024),
    title        VARCHAR(512),
    status       VARCHAR(32) NOT NULL DEFAULT 'pending',
    via          VARCHAR(32),
    error_code   VARCHAR(48),
    source       VARCHAR(64),
    http_status  INT,
    message      TEXT,
    material_id  BIGINT,
    started_at   DATETIME,
    finished_at  DATETIME,
    INDEX idx_crawl_task_job (job_id),
    UNIQUE KEY uniq_crawl_task_idx (job_id, idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS material_analysis (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id       BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    source            VARCHAR(32),
    tags_json         JSON,
    ocr_texts_json    JSON,
    transcript_status VARCHAR(32),
    summary           TEXT,
    issues_json       JSON,
    error             TEXT,
    source_fingerprint VARCHAR(128),
    index_version     VARCHAR(64),
    attempt_count     INT NOT NULL DEFAULT 0,
    indexed_at        DATETIME NULL,
    sample_frames_json JSON,
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
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

CREATE TABLE IF NOT EXISTS job_output (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id      BIGINT NOT NULL,
    idx         INT NOT NULL DEFAULT 0,
    file_path   VARCHAR(1024),
    duration_sec DOUBLE,
    thumbnail   VARCHAR(1024),
    qc_status   VARCHAR(16) DEFAULT 'pass',
    qc_report   TEXT,
    qc_json     TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    hook_strategy VARCHAR(32),
    downgrade_info TEXT,
    used_materials TEXT,
    segment_keys TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_job (job_id),
    UNIQUE KEY uniq_job_output_idx (job_id, idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS output_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_output_id BIGINT,
    job_id BIGINT NOT NULL,
    idx INT NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'rendering',
    file_path VARCHAR(1024),
    duration_sec DOUBLE,
    thumbnail VARCHAR(1024),
    plan_snapshot TEXT,
    params_snapshot TEXT,
    used_materials TEXT,
    repair_strategy TEXT,
    qc_json TEXT,
    qc_report TEXT,
    error TEXT,
    parent_version_no INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_output_version_output (job_output_id),
    INDEX idx_output_version_job_idx (job_id, idx),
    UNIQUE KEY uniq_output_version_job_idx_no (job_id, idx, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS output_edit_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    idx INT NOT NULL,
    base_version_id BIGINT,
    candidate_version_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    plan_snapshot TEXT,
    params_snapshot TEXT,
    comment TEXT,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_output_edit_session_output (job_id, idx),
    INDEX idx_output_edit_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS output_repair (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    output_version_id BIGINT,
    job_output_id BIGINT,
    job_id BIGINT NOT NULL,
    idx INT NOT NULL,
    category VARCHAR(32),
    severity VARCHAR(8),
    issue_id VARCHAR(64),
    evidence TEXT,
    auto_fixable TINYINT(1) NOT NULL DEFAULT 0,
    ai_assessment TEXT,
    recommended_action TEXT,
    candidate_actions TEXT,
    selected_action VARCHAR(64),
    execution_impact TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'proposed',
    before_qc TEXT,
    after_qc TEXT,
    error TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_output_repair_version (output_version_id),
    INDEX idx_output_repair_job (job_id, idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    hook_strategy      VARCHAR(32),
    prefer_human_voice TINYINT(1) NOT NULL DEFAULT 1,
    duck_bgm           TINYINT(1) NOT NULL DEFAULT 1,
    bgm_material_id    BIGINT,
    summary            TEXT,
    created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_editorial_brief_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
