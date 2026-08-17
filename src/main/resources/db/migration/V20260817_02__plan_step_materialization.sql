-- Plan 成功后一次性物化正式 TaskStep 的幂等标记与步骤能力要求。
SET @task_plan_materialized_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'plan_materialized_at'
);
SET @task_plan_materialized_stmt = IF(@task_plan_materialized_exists = 0,
    'ALTER TABLE tasks ADD COLUMN plan_materialized_at DATETIME(6) NULL COMMENT ''Planner 计划已物化为冻结 TaskStep 的时间''',
    'SELECT 1');
PREPARE task_plan_materialized_stmt FROM @task_plan_materialized_stmt;
EXECUTE task_plan_materialized_stmt;
DEALLOCATE PREPARE task_plan_materialized_stmt;

SET @step_capabilities_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_steps' AND COLUMN_NAME = 'required_capabilities'
);
SET @step_capabilities_stmt = IF(@step_capabilities_exists = 0,
    'ALTER TABLE task_steps ADD COLUMN required_capabilities JSON NULL COMMENT ''Planner 需要的 Agent 能力标签'' AFTER acceptance_criteria',
    'SELECT 1');
PREPARE step_capabilities_stmt FROM @step_capabilities_stmt;
EXECUTE step_capabilities_stmt;
DEALLOCATE PREPARE step_capabilities_stmt;
