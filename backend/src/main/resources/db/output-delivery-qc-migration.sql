-- Run once on existing installations before starting the upgraded backend.
-- Adds explainable delivery-QC, retry and material-timeline columns to job_output.
SET @has_retry := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'retry_count');
SET @sql := IF(@has_retry = 0, 'ALTER TABLE job_output ADD COLUMN retry_count INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_strategy := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'hook_strategy');
SET @sql := IF(@has_strategy = 0, 'ALTER TABLE job_output ADD COLUMN hook_strategy VARCHAR(32)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_downgrade := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'downgrade_info');
SET @sql := IF(@has_downgrade = 0, 'ALTER TABLE job_output ADD COLUMN downgrade_info TEXT', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_materials := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'used_materials');
SET @sql := IF(@has_materials = 0, 'ALTER TABLE job_output ADD COLUMN used_materials TEXT', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_qcjson := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'qc_json');
SET @sql := IF(@has_qcjson = 0, 'ALTER TABLE job_output ADD COLUMN qc_json TEXT', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
