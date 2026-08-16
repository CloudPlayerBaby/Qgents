-- ============================================================
-- Workspace 实时 Diff Preview（阶段 D/E）：workspace_diff_previews /
-- workspace_diff_preview_revisions 两张新表。
--
-- 执行方式（云端已存在的库，走增量脚本）：
--   mysql -u <user> -p <database> < V20260816_05__workspace_diff_preview.sql
--
-- 两条都是 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 对应的全量初始化语句已同步维护在 db/qgents_schema.sql。
-- 与正式 Diff（diffs/diff_files）严格分离：preview 只反映 Coding 写过程
-- 中累积的工作树变更，永不作为已 commit/push/MR。
-- ============================================================

CREATE TABLE IF NOT EXISTS workspace_diff_previews (
    id BINARY(16) PRIMARY KEY COMMENT 'Preview 头 UUIDv7',
    workspace_id BINARY(16) NOT NULL COMMENT '预览归属 Workspace（唯一）',
    project_id BINARY(16) NOT NULL COMMENT '所属项目 ID',
    task_id BINARY(16) NOT NULL COMMENT '最近一次写入预览的 Task',
    latest_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最新预览修订号，修订单调递增',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近更新时间（UTC）',
    UNIQUE KEY uk_wdp_workspace (workspace_id),
    KEY idx_wdp_project (project_id),
    CONSTRAINT fk_wdp_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_wdp_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_wdp_task FOREIGN KEY (task_id) REFERENCES tasks(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Workspace 实时 Diff Preview 头';

CREATE TABLE IF NOT EXISTS workspace_diff_preview_revisions (
    id BINARY(16) PRIMARY KEY COMMENT 'Preview 修订 UUIDv7',
    project_id BINARY(16) NOT NULL COMMENT '所属项目 ID',
    task_id BINARY(16) NOT NULL COMMENT '所属 Task',
    task_run_id BINARY(16) NULL COMMENT '产出该修订的 TaskRun',
    workspace_id BINARY(16) NOT NULL COMMENT '预览归属 Workspace',
    revision BIGINT UNSIGNED NOT NULL COMMENT 'Workspace 内单调递增修订号',
    base_commit VARCHAR(128) NULL COMMENT 'Diff 基准 commit',
    working_tree_hash VARCHAR(128) NULL COMMENT '工作树变更摘要哈希（幂等键）',
    snapshot_key VARCHAR(512) NULL COMMENT '受控 patch 快照 key',
    files_changed INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '变更文件数',
    additions INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '新增行数',
    deletions INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '删除行数',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    UNIQUE KEY uk_wdpr_workspace_revision (workspace_id, revision),
    UNIQUE KEY uk_wdpr_workspace_tree_hash (workspace_id, working_tree_hash),
    KEY idx_wdpr_workspace_created (workspace_id, created_at),
    CONSTRAINT fk_wdpr_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_wdpr_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_wdpr_task FOREIGN KEY (task_id) REFERENCES tasks(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Workspace 实时 Diff Preview 修订快照元数据';
