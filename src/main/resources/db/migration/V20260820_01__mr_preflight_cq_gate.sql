-- P1：MR 创建前必须有针对固定 source/target commit 的真实 Dry Run 与独立 CQ+1。
-- 审查事实不能复用 merge_request_reviews：后者依赖已经存在的 MR，无法证明“MR 前”。
CREATE TABLE IF NOT EXISTS preflight_cq_reviews (
    id BINARY(16) PRIMARY KEY COMMENT 'MR创建前CQ审查UUIDv7',
    project_id BINARY(16) NOT NULL COMMENT '所属项目ID',
    task_id BINARY(16) NOT NULL COMMENT '待交付Task ID',
    project_repository_id BINARY(16) NOT NULL COMMENT '项目仓库绑定ID',
    dry_run_id BINARY(16) NOT NULL COMMENT '已完成Dry Run ID',
    source_commit VARCHAR(128) NOT NULL COMMENT 'Dry Run固定的源提交SHA',
    target_branch VARCHAR(512) NOT NULL COMMENT '目标分支名',
    target_commit VARCHAR(128) NOT NULL COMMENT 'Dry Run固定的目标基准SHA',
    decision VARCHAR(16) NOT NULL COMMENT '审查结论：APPROVED/REJECTED',
    reviewer_user_id BINARY(16) NOT NULL COMMENT '独立CQ审查者用户ID',
    reason VARCHAR(1000) NULL COMMENT 'CQ理由或拒绝修改意见',
    reviewed_at DATETIME(6) NOT NULL COMMENT '审查时间UTC',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录创建时间UTC',
    -- target_branch 使用前缀索引：utf8mb4 下完整 512 字符列会使复合索引超过 InnoDB 3072 字节上限，等值查询仍可命中前缀。
    KEY idx_preflight_cq_context (project_id, task_id, project_repository_id, source_commit, target_branch(255), target_commit, reviewed_at),
    KEY idx_preflight_cq_dry_run (dry_run_id, reviewed_at),
    CONSTRAINT fk_preflight_cq_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_preflight_cq_task FOREIGN KEY (task_id) REFERENCES tasks (id),
    CONSTRAINT fk_preflight_cq_repo FOREIGN KEY (project_repository_id) REFERENCES project_repositories (id),
    CONSTRAINT fk_preflight_cq_dry_run FOREIGN KEY (dry_run_id) REFERENCES dry_runs (id),
    CONSTRAINT fk_preflight_cq_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES users (id),
    CHECK (decision IN ('APPROVED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '绑定固定提交的MR创建前CQ审查事实';
