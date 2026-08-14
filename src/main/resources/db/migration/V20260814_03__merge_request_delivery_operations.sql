-- 幂等化：merge_requests 三列存在性探测 + PREPARE 动态执行；表用 IF NOT EXISTS。
-- 对已存在列的库自动跳过 ALTER，整体可重复执行，避免 Duplicate column name。
SET @mr_op_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests' AND COLUMN_NAME = 'merge_operation_id'
);
SET @mr_op_alter_sql = IF(@mr_op_col_exists = 0,
    'ALTER TABLE merge_requests ADD COLUMN merge_operation_id VARCHAR(64) NULL COMMENT ''受控合并幂等操作 ID'' AFTER quality_gate_status, ADD COLUMN merge_operation_status VARCHAR(32) NOT NULL DEFAULT ''NOT_STARTED'' COMMENT ''NOT_STARTED/RUNNING/COMPLETED/FAILED'' AFTER merge_operation_id, ADD COLUMN merge_lease_expires_at DATETIME(6) NULL COMMENT ''合并操作租约到期时间 UTC'' AFTER merge_operation_status',
    'SELECT 1');
PREPARE mr_op_alter_stmt FROM @mr_op_alter_sql;
EXECUTE mr_op_alter_stmt;
DEALLOCATE PREPARE mr_op_alter_stmt;

CREATE TABLE IF NOT EXISTS merge_request_delivery_operations (
    id BINARY(16) PRIMARY KEY COMMENT 'MR 创建操作 UUIDv7',
    operation_key CHAR(64) NOT NULL COMMENT '项目、Task、Workspace、仓库、分支与 HEAD 组成的 SHA-256 幂等键',
    project_id BINARY(16) NOT NULL COMMENT '所属项目 ID',
    project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定 ID',
    task_id BINARY(16) NOT NULL COMMENT '来源 Task ID',
    workspace_id BINARY(16) NOT NULL COMMENT '来源 Workspace ID',
    actor_user_id BINARY(16) NOT NULL COMMENT '发起用户 ID',
    source_branch VARCHAR(512) NOT NULL COMMENT '源分支',
    target_branch VARCHAR(512) NOT NULL COMMENT '目标分支',
    head_commit VARCHAR(128) NOT NULL COMMENT '领取时固定的 HEAD SHA',
    title TEXT NULL COMMENT 'MR 标题',
    status VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/REMOTE_CREATED/COMPLETED/FAILED',
    claim_token VARCHAR(64) NULL COMMENT '当前执行 fencing token',
    lease_expires_at DATETIME(6) NULL COMMENT '执行租约到期时间 UTC',
    provider_number BIGINT UNSIGNED NULL COMMENT 'GitHub 真实 PR 编号',
    merge_request_id BINARY(16) NULL COMMENT '最终本地 MR 镜像 ID',
    failure_code VARCHAR(128) NULL COMMENT '最近失败码',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_mr_delivery_operation_key (operation_key),
    KEY idx_mr_delivery_recovery (status, lease_expires_at),
    CONSTRAINT fk_mr_delivery_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_mr_delivery_repository FOREIGN KEY (project_repository_id) REFERENCES project_repositories(id),
    CONSTRAINT fk_mr_delivery_task FOREIGN KEY (task_id) REFERENCES tasks(id),
    CONSTRAINT fk_mr_delivery_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_mr_delivery_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT fk_mr_delivery_result FOREIGN KEY (merge_request_id) REFERENCES merge_requests(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复的 MR push 与远端创建操作';
