-- 任务可靠性增量迁移（MySQL 8）。请连接应用实际数据库后执行；重复执行安全。

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN timeout_sec INT NOT NULL DEFAULT 0',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'timeout_sec');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN stale_after_sec INT NOT NULL DEFAULT 0',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'stale_after_sec');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job ADD COLUMN last_activity_at DATETIME NULL',
  'SELECT 1') FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'last_activity_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE job SET last_activity_at = COALESCE(last_activity_at, updated_at, created_at, CURRENT_TIMESTAMP)
WHERE last_activity_at IS NULL;

-- 旧版本没有唯一约束时若曾出现重复检查点，保留最早记录后再加约束。
DELETE newer FROM job_output newer
JOIN job_output older
  ON older.job_id = newer.job_id AND older.idx = newer.idx AND older.id < newer.id;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE job_output ADD UNIQUE KEY uniq_job_output_idx (job_id, idx)',
  'SELECT 1') FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'job_output' AND index_name = 'uniq_job_output_idx');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
