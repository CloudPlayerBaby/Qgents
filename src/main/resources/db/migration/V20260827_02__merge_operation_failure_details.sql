-- 保留异步 GitHub 合并失败的受控原因，供 MR 列表和 SSE 更新展示。
SET @mr_failure_cols = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests'
      AND COLUMN_NAME = 'merge_operation_failure_code'
);
SET @mr_failure_alter_sql = IF(@mr_failure_cols = 0,
    'ALTER TABLE merge_requests ADD COLUMN merge_operation_failure_code VARCHAR(128) NULL COMMENT ''最近一次合并失败码'' AFTER merge_operation_status, ADD COLUMN merge_operation_failure_reason VARCHAR(512) NULL COMMENT ''最近一次合并失败原因'' AFTER merge_operation_failure_code',
    'SELECT 1');
PREPARE mr_failure_alter_stmt FROM @mr_failure_alter_sql;
EXECUTE mr_failure_alter_stmt;
DEALLOCATE PREPARE mr_failure_alter_stmt;
