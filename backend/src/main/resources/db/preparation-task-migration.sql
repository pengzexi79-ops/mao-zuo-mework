-- 出片准备任务迁移（MySQL 8），请在应用实际数据库中执行，可重复执行。
-- 运行时 RenderPreparationService 启动/首次请求时也会幂等执行相同 DDL，本文件供运维与安装器参考。
CREATE TABLE IF NOT EXISTS preparation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(32) NOT NULL DEFAULT 'running',
    project_id BIGINT,
    params JSON,
    use_ai TINYINT(1) NOT NULL DEFAULT 1,
    wait_seconds INT NOT NULL DEFAULT 45,
    keyword VARCHAR(255),
    ai_used TINYINT(1) NOT NULL DEFAULT 0,
    ready TINYINT(1) NOT NULL DEFAULT 0,
    timed_out TINYINT(1) NOT NULL DEFAULT 0,
    waited_seconds INT NOT NULL DEFAULT 0,
    crawl_job_ids JSON,
    stages JSON,
    initial_gap JSON,
    final_gap JSON,
    auto_fill JSON,
    error TEXT,
    last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_preparation_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
