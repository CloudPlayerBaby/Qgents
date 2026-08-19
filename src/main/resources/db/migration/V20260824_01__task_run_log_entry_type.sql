-- TaskRun 日志区分真实执行记录、生命周期系统记录和终态摘要。
-- 已存在的历史记录按真实执行日志兼容，不修改原始 content。
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'execution_logs' AND column_name = 'entry_type'
);
SET @sql = IF(@column_exists = 0,
              'ALTER TABLE execution_logs ADD COLUMN entry_type VARCHAR(16) NOT NULL DEFAULT ''EXECUTION'' COMMENT ''日志来源：EXECUTION/SYSTEM/TERMINAL'' AFTER sequence_no',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'execution_logs' AND index_name = 'idx_log_run_type'
);
SET @sql = IF(@index_exists = 0,
              'ALTER TABLE execution_logs ADD KEY idx_log_run_type (task_run_id, entry_type, sequence_no)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
