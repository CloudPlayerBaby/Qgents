-- 持久化真实 MR 创建的租约和重试状态，避免事件丢失后永久停留在 CREATING_MR。
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'mr_preflight_requests'
      AND column_name = 'mr_creation_attempt_count'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE mr_preflight_requests ADD COLUMN mr_creation_attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''真实 MR 创建尝试次数'' AFTER idempotency_key',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'mr_preflight_requests'
      AND column_name = 'mr_creation_next_attempt_at'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE mr_preflight_requests ADD COLUMN mr_creation_next_attempt_at DATETIME(6) NULL COMMENT ''真实 MR 创建租约/下次重试时间'' AFTER mr_creation_attempt_count',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
