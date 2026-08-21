-- 喵作 · Mework：V1 -> V2 安全升级脚本（MySQL 8）
--
-- 用法：mysql -uroot -p ai_mix_video < docs/UPGRADE_V1_TO_V2.sql
--
-- 本脚本只增加 job 可靠性字段与索引；不删除数据、不重建表、不添加外键。
-- 每个 DDL 均通过 information_schema 保护，重复执行安全。
-- 请在备份完成、应用停止写入或低峰期执行；索引创建可能短暂占用表元数据锁。

-- 不指定 USE；调用命令必须显式传入目标数据库，避免误迁移到错误实例。

-- 任务总超时。0 表示使用应用级默认值。
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `job` ADD COLUMN `timeout_sec` INT NOT NULL DEFAULT 0',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'timeout_sec'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 无活动判定阈值。0 表示使用应用级默认值。
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `job` ADD COLUMN `stale_after_sec` INT NOT NULL DEFAULT 0',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'stale_after_sec'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Watchdog 依赖的最后活动时间。
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `job` ADD COLUMN `last_activity_at` DATETIME NULL',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'last_activity_at'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `job`
SET `last_activity_at` = COALESCE(`last_activity_at`, `updated_at`, `created_at`, CURRENT_TIMESTAMP)
WHERE `last_activity_at` IS NULL;

-- job_output 的幂等检查点依赖 (job_id, idx) 唯一性。
-- 不自动删除历史重复记录。若下方预检返回数据，请先备份并人工处理，
-- 再重新执行本脚本；这样不会以“升级”为名丢失任务产物记录。
SELECT `job_id`, `idx`, COUNT(*) AS `duplicate_count`
FROM `job_output`
GROUP BY `job_id`, `idx`
HAVING COUNT(*) > 1;

SET @job_output_unique_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'job_output'
      AND index_name = 'uniq_job_output_idx'
);
SET @job_output_duplicate_groups = (
    SELECT COUNT(*)
    FROM (
        SELECT `job_id`, `idx`
        FROM `job_output`
        GROUP BY `job_id`, `idx`
        HAVING COUNT(*) > 1
    ) AS duplicate_groups
);
SET @sql = IF(@job_output_unique_exists = 0 AND @job_output_duplicate_groups = 0,
    'ALTER TABLE `job_output` ADD UNIQUE KEY `uniq_job_output_idx` (`job_id`, `idx`)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 使用 file_path 定位已扫描素材，避免全表扫描。
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `material` ADD KEY `idx_file_path` (`file_path`(255))',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'material'
      AND index_name = 'idx_file_path'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.2.30 补齐当前任务详情与素材管理实体字段。只新增字段，不修改或删除旧数据。
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `job` ADD COLUMN `current_step` VARCHAR(512) NULL', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'current_step');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `job` ADD COLUMN `phase_progress` INT NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'job' AND column_name = 'phase_progress');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `material` ADD COLUMN `mute_original_audio` TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material' AND column_name = 'mute_original_audio');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE `material` ADD COLUMN `transcribe_for_subtitles` TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material' AND column_name = 'transcribe_for_subtitles');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
