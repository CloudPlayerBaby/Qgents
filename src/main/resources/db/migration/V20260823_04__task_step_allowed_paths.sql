-- Persist the Planner's per-step write boundary. NULL keeps pre-migration
-- hand-authored/history steps compatible; newly materialized steps populate it.
SET @allowed_paths_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_steps' AND COLUMN_NAME = 'allowed_paths'
);
SET @allowed_paths_stmt = IF(@allowed_paths_exists = 0,
    'ALTER TABLE task_steps ADD COLUMN allowed_paths JSON NULL COMMENT ''当前步骤允许写入的 Workspace 相对路径'' AFTER required_capabilities',
    'SELECT 1');
PREPARE allowed_paths_stmt FROM @allowed_paths_stmt;
EXECUTE allowed_paths_stmt;
DEALLOCATE PREPARE allowed_paths_stmt;
