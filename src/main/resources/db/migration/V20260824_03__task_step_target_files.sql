-- Persist the Planner's per-step target files (Workspace-relative). NULL keeps
-- pre-migration hand-authored/history steps compatible; newly materialized steps
-- populate it so the Coding runtime can decide whether declared targets are
-- already satisfied in the workspace before treating zero writes as a hard failure.
SET @target_files_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_steps' AND COLUMN_NAME = 'target_files'
);
SET @target_files_stmt = IF(@target_files_exists = 0,
    'ALTER TABLE task_steps ADD COLUMN target_files JSON NULL COMMENT ''当前步骤声明的目标文件（Workspace 相对路径），用于目标已满足判定'' AFTER allowed_paths',
    'SELECT 1');
PREPARE target_files_stmt FROM @target_files_stmt;
EXECUTE target_files_stmt;
DEALLOCATE PREPARE target_files_stmt;
