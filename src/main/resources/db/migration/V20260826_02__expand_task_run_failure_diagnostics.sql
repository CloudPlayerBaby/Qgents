ALTER TABLE task_run_failure_diagnostics
    ADD COLUMN run_outcome VARCHAR(32) NOT NULL DEFAULT 'FAILED_INFRASTRUCTURE' COMMENT '失败语义：FAILED/FAILED_QUALITY/FAILED_INFRASTRUCTURE' AFTER phase,
    ADD COLUMN step_role VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '失败步骤角色' AFTER run_outcome,
    ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' COMMENT '失败步骤执行模式' AFTER step_role,
    ADD COLUMN diagnostic_context JSON NULL COMMENT '按失败相位保存的脱敏结构化上下文，不保存原始命令或输出' AFTER failure_detail;
