-- 后台采集任务迁移（MySQL 8），请在应用实际数据库中执行，可重复执行。
CREATE TABLE IF NOT EXISTS crawl_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), mode VARCHAR(32) NOT NULL DEFAULT 'video',
    role VARCHAR(32) NOT NULL DEFAULT 'body', status VARCHAR(32) NOT NULL DEFAULT 'pending', progress INT NOT NULL DEFAULT 0,
    current_item INT NOT NULL DEFAULT 0, total INT NOT NULL DEFAULT 0, params JSON, summary TEXT, error TEXT,
    timeout_sec INT NOT NULL DEFAULT 0, stale_after_sec INT NOT NULL DEFAULT 0, last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_crawl_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS crawl_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT NOT NULL, idx INT NOT NULL, url VARCHAR(1024), title VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'pending', via VARCHAR(32), error_code VARCHAR(48), source VARCHAR(64), http_status INT, message TEXT, material_id BIGINT,
    started_at DATETIME, finished_at DATETIME, INDEX idx_crawl_task_job (job_id), UNIQUE KEY uniq_crawl_task_idx (job_id, idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
