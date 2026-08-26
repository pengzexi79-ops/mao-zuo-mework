-- Run once on existing installations before starting the upgraded backend.
SET @has_qc_status := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'qc_status');
SET @sql := IF(@has_qc_status = 0, 'ALTER TABLE job_output ADD COLUMN qc_status VARCHAR(16) NOT NULL DEFAULT ''pass''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_qc_report := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'qc_report');
SET @sql := IF(@has_qc_report = 0, 'ALTER TABLE job_output ADD COLUMN qc_report TEXT', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
