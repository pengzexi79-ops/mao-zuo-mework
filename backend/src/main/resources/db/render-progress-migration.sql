-- 渲染进度持久化迁移（MySQL 8）。请在应用实际数据库中执行，可重复执行。
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE job ADD COLUMN current_step VARCHAR(512)', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'current_step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE job ADD COLUMN phase_progress INT NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'phase_progress');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
