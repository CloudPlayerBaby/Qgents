-- Delivery Center 首屏查询索引。
-- 每条语句均通过 information_schema 判断，允许在已有环境重复执行而不报错。

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'workspaces'
         AND index_name = 'idx_workspace_project_id') = 0,
    'ALTER TABLE workspaces ADD KEY idx_workspace_project_id (project_id, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'diff_review_batches'
         AND index_name = 'idx_diff_batch_project_updated') = 0,
    'ALTER TABLE diff_review_batches ADD KEY idx_diff_batch_project_updated (project_id, updated_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'diffs'
         AND index_name = 'idx_diff_review_batch_repository') = 0,
    'ALTER TABLE diffs ADD KEY idx_diff_review_batch_repository (review_batch_id, project_repository_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'tasks'
         AND index_name = 'idx_task_project_workspace_updated') = 0,
    'ALTER TABLE tasks ADD KEY idx_task_project_workspace_updated (project_id, workspace_id, updated_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = @schema_name AND table_name = 'merge_requests'
         AND index_name = 'idx_mr_repository_status_id') = 0,
    'ALTER TABLE merge_requests ADD KEY idx_mr_repository_status_id (project_repository_id, status, id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
