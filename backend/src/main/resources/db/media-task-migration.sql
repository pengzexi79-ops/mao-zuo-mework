-- Persisted local media-tool tasks. Safe to run repeatedly on MySQL 8.
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
    INDEX idx_media_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
