ALTER TABLE tasks
    ADD COLUMN context_snapshot JSON NULL COMMENT 'Task 创建时冻结的默认 Agent 上下文，仅内部恢复使用'
    AFTER requirement;
