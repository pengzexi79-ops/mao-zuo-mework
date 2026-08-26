-- Safe migration: add user-explicit material flags for muteOriginalAudio and transcribeForSubtitles.
-- Uses the same IF NOT EXISTS pattern as existing migrations.

SET @has_mute_audio := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material' AND COLUMN_NAME = 'mute_original_audio');
SET @sql := IF(@has_mute_audio = 0, 'ALTER TABLE material ADD COLUMN mute_original_audio TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_transcribe := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'material' AND COLUMN_NAME = 'transcribe_for_subtitles');
SET @sql := IF(@has_transcribe = 0, 'ALTER TABLE material ADD COLUMN transcribe_for_subtitles TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
