-- Persistent Job execution fencing (MySQL 8). Safe to run repeatedly.

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN version BIGINT NOT NULL DEFAULT 0',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'version');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'execution_epoch');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN lease_token VARCHAR(64) NULL',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'lease_token');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN lease_expires_at DATETIME NULL',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'lease_expires_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
