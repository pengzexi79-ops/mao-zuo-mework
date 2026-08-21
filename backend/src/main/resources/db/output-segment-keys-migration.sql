-- Add durable source-slice keys so resumed jobs never forget already used footage.
SET @has_segment_keys := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_output' AND COLUMN_NAME = 'segment_keys');
SET @sql := IF(@has_segment_keys = 0, 'ALTER TABLE job_output ADD COLUMN segment_keys TEXT', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
