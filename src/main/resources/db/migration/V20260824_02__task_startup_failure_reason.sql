-- Persist a safe, user-visible reason when orchestration fails before a TaskRun exists.
-- Existing task rows remain NULL; retry/start paths clear the fields.
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'tasks' AND column_name = 'failure_code'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE tasks ADD COLUMN failure_code VARCHAR(64) NULL COMMENT ''Task 级启动/编排失败稳定码'' AFTER delivery_reason, ADD COLUMN failure_reason VARCHAR(1024) NULL COMMENT ''Task 级脱敏失败说明'' AFTER failure_code, ADD COLUMN failure_retryable TINYINT(1) NULL COMMENT ''Task 级失败是否允许重试'' AFTER failure_reason, ADD COLUMN failure_occurred_at DATETIME(6) NULL COMMENT ''Task 级失败发生时间（UTC）'' AFTER failure_retryable',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
