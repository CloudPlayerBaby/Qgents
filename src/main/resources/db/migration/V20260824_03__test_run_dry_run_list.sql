-- Test Run / Dry Run 列表需要真实的执行生命周期时间和按创建时间游标分页索引。
-- 使用元数据判断保证重复执行不会因已有列或索引而中断。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND column_name = 'started_at') = 0,
    'ALTER TABLE test_runs ADD COLUMN started_at DATETIME(6) NULL COMMENT ''开始执行时间（UTC）'' AFTER status',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND column_name = 'finished_at') = 0,
    'ALTER TABLE test_runs ADD COLUMN finished_at DATETIME(6) NULL COMMENT ''结束执行时间（UTC）'' AFTER started_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'dry_runs' AND column_name = 'started_at') = 0,
    'ALTER TABLE dry_runs ADD COLUMN started_at DATETIME(6) NULL COMMENT ''开始执行时间（UTC）'' AFTER status',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'dry_runs' AND column_name = 'finished_at') = 0,
    'ALTER TABLE dry_runs ADD COLUMN finished_at DATETIME(6) NULL COMMENT ''结束执行时间（UTC）'' AFTER started_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND index_name = 'idx_test_run_list') = 0,
    'ALTER TABLE test_runs ADD KEY idx_test_run_list (project_id, created_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'dry_runs' AND index_name = 'idx_dry_run_list') = 0,
    'ALTER TABLE dry_runs ADD KEY idx_dry_run_list (project_id, created_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
