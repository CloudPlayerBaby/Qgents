-- 适用于已部署过旧版 sandbox_worker_schema.sql 的数据库，仅执行一次。
-- 执行前应停止所有 Worker；启动新版 Worker 前将历史活动执行统一标记为中断。

ALTER TABLE tool_executions
    ADD COLUMN owner_worker_id VARCHAR(128) NULL AFTER id;

UPDATE tool_executions
SET owner_worker_id = 'legacy-worker'
WHERE owner_worker_id IS NULL;

UPDATE tool_executions
SET status = 'INTERRUPTED',
    failure_reason = 'Worker 版本升级，历史活动执行已中断',
    finished_at = UTC_TIMESTAMP(6)
WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE tool_executions
    MODIFY COLUMN owner_worker_id VARCHAR(128) NOT NULL COMMENT '实际运行该执行的 Worker 编号',
    DROP COLUMN request_hash;

DROP INDEX idx_tool_execution_sandbox ON tool_executions;

CREATE INDEX idx_tool_execution_sandbox
    ON tool_executions (sandbox_id, owner_worker_id, created_at);

ALTER TABLE tool_executions
    ADD CONSTRAINT chk_tool_execution_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED')
    );

ALTER TABLE tool_execution_logs
    ADD CONSTRAINT chk_tool_execution_log_stream CHECK (stream IN ('SYSTEM', 'STDOUT', 'STDERR'));
