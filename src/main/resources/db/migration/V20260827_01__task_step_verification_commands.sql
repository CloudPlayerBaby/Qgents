-- Persist the Planner's structured per-repository verification commands on the
-- TEST step. NULL keeps pre-migration steps compatible; newly materialized TEST
-- steps populate it so the Test Agent can consume the commands during normal
-- runs and after resume (where the in-memory PlanResult is null). The column
-- stores only whitelisted command templates (mvn/gradle/npm test); arbitrary
-- shell, file paths and module parameters are rejected at parse time.
SET @verification_commands_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_steps' AND COLUMN_NAME = 'verification_commands'
);
SET @verification_commands_stmt = IF(@verification_commands_exists = 0,
    'ALTER TABLE task_steps ADD COLUMN verification_commands JSON NULL COMMENT ''Planner 冻结的按仓库验证命令（白名单模板），TEST 步骤专用'' AFTER execution_mode',
    'SELECT 1');
PREPARE verification_commands_stmt FROM @verification_commands_stmt;
EXECUTE verification_commands_stmt;
DEALLOCATE PREPARE verification_commands_stmt;
