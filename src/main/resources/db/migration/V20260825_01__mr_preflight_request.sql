-- 分支级 MR 预检申请事实：用户/交付事件“申请创建 MR”这一业务流程。
-- 同一 (repository, source_branch, target_branch, head_commit, target_commit) 只能有一个活跃申请，
-- 保证重复点击/重复投递返回同一预检，并让旧上下文失效由新上下文替代。
CREATE TABLE IF NOT EXISTS mr_preflight_requests (
    id BINARY(16) PRIMARY KEY COMMENT '预检申请UUIDv7',
    project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
    trigger_task_id BINARY(16) NOT NULL COMMENT '触发申请的任务ID；分支级重试/汇总可为任一覆盖任务',
    project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定ID',
    workspace_id BINARY(16) NOT NULL COMMENT '来源Workspace ID',
    source_branch VARCHAR(512) NOT NULL COMMENT '源分支',
    target_branch VARCHAR(512) NOT NULL COMMENT '目标分支（来自Workspace baseRef，客户端不可覆盖）',
    context_hash CHAR(64) NOT NULL COMMENT 'repository/source/target/head/targetCommit 的 SHA-256 幂等键',
    head_commit VARCHAR(128) NOT NULL COMMENT '申请时固定的源提交SHA',
    target_commit VARCHAR(128) NOT NULL COMMENT '申请时由Git Store解析固定的目标提交SHA',
    dry_run_id BINARY(16) NULL COMMENT '本次预检关联的Dry Run ID',
    status VARCHAR(32) NOT NULL COMMENT '状态：REQUESTED/DRY_RUN_QUEUED/DRY_RUN_RUNNING/WAITING_CQ/CQ_REJECTED/CREATING_MR/MR_CREATED/FAILED/STALE',
    requested_by BINARY(16) NOT NULL COMMENT '发起用户ID（MR_FIRST 为Task创建人）',
    idempotency_key VARCHAR(128) NULL COMMENT '客户端幂等键，同一键返回同一预检',
    failure_code VARCHAR(128) NULL COMMENT '稳定失败分类码',
    failure_reason VARCHAR(1000) NULL COMMENT '用户可读失败原因',
    merge_request_id BINARY(16) NULL COMMENT '最终真实MR镜像ID',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_mr_preflight_context_hash (context_hash),
    UNIQUE KEY uk_mr_preflight_idempotency (idempotency_key),
    KEY idx_mr_preflight_task (project_id, trigger_task_id, created_at),
    KEY idx_mr_preflight_dry_run (dry_run_id),
    KEY idx_mr_preflight_status (status, updated_at),
    CONSTRAINT fk_mr_preflight_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_mr_preflight_task FOREIGN KEY (trigger_task_id) REFERENCES tasks (id),
    CONSTRAINT fk_mr_preflight_repository FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id),
    CONSTRAINT fk_mr_preflight_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_mr_preflight_dry_run FOREIGN KEY (dry_run_id) REFERENCES dry_runs (id),
    CONSTRAINT fk_mr_preflight_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT fk_mr_preflight_result FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分支级MR预检申请事实';

-- 预检与 Task 的多对多关联：同一 source branch 覆盖的多个已交付 Task。
CREATE TABLE IF NOT EXISTS mr_preflight_tasks (
    preflight_id BINARY(16) NOT NULL COMMENT '预检申请ID',
    task_id BINARY(16) NOT NULL COMMENT '覆盖的Task ID',
    role VARCHAR(32) NOT NULL COMMENT 'TRIGGER/COVERED',
    PRIMARY KEY (preflight_id, task_id),
    KEY idx_mr_preflight_tasks_task (task_id),
    CONSTRAINT fk_mr_preflight_tasks_preflight FOREIGN KEY (preflight_id) REFERENCES mr_preflight_requests (id),
    CONSTRAINT fk_mr_preflight_tasks_task FOREIGN KEY (task_id) REFERENCES tasks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分支级预检覆盖的Task关联事实';
