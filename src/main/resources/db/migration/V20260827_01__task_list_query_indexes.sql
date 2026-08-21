-- 任务中心列表只读取每个步骤的最新运行和每个任务最新失败运行。
-- 通过 information_schema 判断，允许在已有环境重复执行。

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'task_runs'
         AND index_name = 'idx_task_run_list_latest') = 0,
    'ALTER TABLE task_runs ADD KEY idx_task_run_list_latest (task_id, task_step_id, created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'task_runs'
         AND index_name = 'idx_task_run_list_failed') = 0,
    'ALTER TABLE task_runs ADD KEY idx_task_run_list_failed (task_id, status, failure_occurred_at, updated_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
