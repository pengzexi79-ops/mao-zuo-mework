-- 素材文件夹元数据迁移（MySQL 8）。请在应用实际数据库中执行，可重复执行。
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE material_folder ADD COLUMN description TEXT', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_folder' AND column_name = 'description');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE material_folder ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_folder' AND column_name = 'enabled');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE material_folder ADD COLUMN sort_order INT NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'material_folder' AND column_name = 'sort_order');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
