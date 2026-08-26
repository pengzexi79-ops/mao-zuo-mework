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
