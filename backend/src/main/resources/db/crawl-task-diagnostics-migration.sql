-- Durable crawl diagnostics. Each statement is idempotent on MySQL 8.
SET @has_error_code := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'crawl_task' AND column_name = 'error_code');
SET @sql := IF(@has_error_code = 0, 'ALTER TABLE crawl_task ADD COLUMN error_code VARCHAR(48)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_source := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'crawl_task' AND column_name = 'source');
SET @sql := IF(@has_source = 0, 'ALTER TABLE crawl_task ADD COLUMN source VARCHAR(64)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_http_status := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'crawl_task' AND column_name = 'http_status');
SET @sql := IF(@has_http_status = 0, 'ALTER TABLE crawl_task ADD COLUMN http_status INT', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
