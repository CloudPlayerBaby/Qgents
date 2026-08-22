-- MR 状态通知幂等键，避免 webhook、轮询和手动刷新重复写入同一状态。
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'dedupe_key'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE notifications ADD COLUMN dedupe_key VARCHAR(256) NULL COMMENT ''通知幂等键；MR_PENDING 为资源ID与状态组合''',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'notifications' AND index_name = 'uk_notif_recipient_dedupe'
);
SET @sql = IF(@index_exists = 0,
              'ALTER TABLE notifications ADD UNIQUE KEY uk_notif_recipient_dedupe (recipient_user_id, dedupe_key)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
