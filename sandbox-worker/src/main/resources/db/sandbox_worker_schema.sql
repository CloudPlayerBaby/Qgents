CREATE TABLE IF NOT EXISTS tool_executions (
    id CHAR(36) PRIMARY KEY COMMENT '执行编号',
    owner_worker_id VARCHAR(128) NOT NULL COMMENT '实际运行该执行的 Worker 编号',
    sandbox_id CHAR(36) NOT NULL COMMENT '沙箱编号',
    repository_id CHAR(36) NULL COMMENT '仓库编号，通用命令可以为空',
    tool_name VARCHAR(64) NOT NULL COMMENT '服务端注册的工具名称',
    arguments_json JSON NOT NULL COMMENT '工具结构化参数',
    status VARCHAR(24) NOT NULL COMMENT '执行状态',
    exit_code INT NULL COMMENT '进程类工具退出码',
    result_json JSON NULL COMMENT '结构化工具结果',
    failure_reason VARCHAR(1024) NULL COMMENT '失败原因',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    started_at DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at DATETIME(6) NULL COMMENT '结束时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_tool_execution_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED')
    ),
    KEY idx_tool_execution_sandbox (sandbox_id, owner_worker_id, created_at),
    KEY idx_tool_execution_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱工具执行记录';

CREATE TABLE IF NOT EXISTS tool_execution_logs (
    execution_id CHAR(36) NOT NULL COMMENT '工具执行编号',
    sequence_no BIGINT NOT NULL COMMENT '执行内递增序号',
    stream VARCHAR(16) NOT NULL COMMENT '日志流类型',
    content TEXT NOT NULL COMMENT '经过长度限制的日志内容',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT chk_tool_execution_log_stream CHECK (stream IN ('SYSTEM', 'STDOUT', 'STDERR')),
    PRIMARY KEY (execution_id, sequence_no),
    CONSTRAINT fk_tool_execution_log_execution FOREIGN KEY (execution_id)
        REFERENCES tool_executions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='沙箱工具执行日志';
