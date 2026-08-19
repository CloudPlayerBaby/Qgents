-- 固化 TaskStep 执行语义，避免验证步骤误按必须修改文件的 Coding Step 执行。
SET @execution_mode_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_steps' AND COLUMN_NAME = 'execution_mode'
);
SET @execution_mode_stmt = IF(@execution_mode_exists = 0,
    'ALTER TABLE task_steps ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT ''MUTATE'' COMMENT ''步骤执行语义：MUTATE/VERIFY/TEST/REVIEW/PLAN'' AFTER required_capabilities',
    'SELECT 1');
PREPARE execution_mode_stmt FROM @execution_mode_stmt;
EXECUTE execution_mode_stmt;
DEALLOCATE PREPARE execution_mode_stmt;

UPDATE task_steps
SET execution_mode = CASE role
    WHEN 'PLANNER' THEN 'PLAN'
    WHEN 'TESTER' THEN 'TEST'
    WHEN 'REVIEWER' THEN 'REVIEW'
    ELSE 'MUTATE'
END;
