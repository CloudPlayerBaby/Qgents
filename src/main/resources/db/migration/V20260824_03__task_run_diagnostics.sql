-- TaskRun 失败诊断：每次失败持久化稳定失败码/脱敏摘要，并保留 Worker 工具执行的结构化关联。
-- 迁移对已升级或局部升级数据库幂等；完整结构同步见 qgents_schema.sql。

SET @has_failure_code = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'task_runs' AND column_name = 'failure_code'
);
SET @sql = IF(@has_failure_code = 0,
    'ALTER TABLE task_runs ADD COLUMN failure_code VARCHAR(64) NULL COMMENT ''本次运行失败稳定码，仅FAILED时非空'' AFTER retry_of_task_run_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_failure_reason = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'task_runs' AND column_name = 'failure_reason'
);
SET @sql = IF(@has_failure_reason = 0,
    'ALTER TABLE task_runs ADD COLUMN failure_reason VARCHAR(1024) NULL COMMENT ''本次运行脱敏失败说明，仅FAILED时非空'' AFTER failure_code',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_failure_occurred_at = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'task_runs' AND column_name = 'failure_occurred_at'
);
SET @sql = IF(@has_failure_occurred_at = 0,
    'ALTER TABLE task_runs ADD COLUMN failure_occurred_at DATETIME(6) NULL COMMENT ''失败发生时间（UTC）'' AFTER failure_reason',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS task_run_worker_executions (
    execution_id BINARY(16) PRIMARY KEY COMMENT 'Worker 工具执行ID',
    project_id BINARY(16) NOT NULL COMMENT '所属项目ID，用于隔离查询',
    task_id BINARY(16) NOT NULL COMMENT '所属任务ID',
    task_run_id BINARY(16) NOT NULL COMMENT '所属任务运行ID',
    sandbox_id BINARY(16) NULL COMMENT 'Worker Sandbox ID',
    repository_id BINARY(16) NULL COMMENT '目标项目仓库ID',
    tool_name VARCHAR(64) NULL COMMENT 'Worker 工具名称',
    status VARCHAR(24) NULL COMMENT 'Worker 工具状态',
    exit_code INT NULL COMMENT '进程类工具退出码',
    failure_code VARCHAR(64) NULL COMMENT 'Worker 稳定失败码',
    failure_reason VARCHAR(1024) NULL COMMENT 'Worker 脱敏失败摘要',
    created_at DATETIME(6) NOT NULL COMMENT 'Worker 接收执行时间（UTC）',
    started_at DATETIME(6) NULL COMMENT 'Worker 开始执行时间（UTC）',
    finished_at DATETIME(6) NULL COMMENT 'Worker 完成时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_run_worker_execution_run (project_id, task_run_id, created_at),
    KEY idx_run_worker_execution_task (task_id, created_at),
    CONSTRAINT fk_run_worker_execution_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_run_worker_execution_task FOREIGN KEY (task_id) REFERENCES tasks (id),
    CONSTRAINT fk_run_worker_execution_run FOREIGN KEY (task_run_id) REFERENCES task_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TaskRun 与 Worker 工具执行的脱敏诊断关联';
