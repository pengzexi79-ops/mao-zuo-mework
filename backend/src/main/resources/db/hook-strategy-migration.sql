-- 幂等迁移：为 editorial_brief 增加任务级钩子策略字段（Phase 3 多类型钩子策略）。
-- 新安装由 BootstrapService 直接建表；已存在安装由 migrateEditorialBriefHookStrategy 补齐。

-- 仅当列不存在时新增，避免重复执行报错。
SET @db = DATABASE();
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @db AND table_name = 'editorial_brief' AND column_name = 'hook_strategy'
);

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE editorial_brief ADD COLUMN hook_strategy VARCHAR(32)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
