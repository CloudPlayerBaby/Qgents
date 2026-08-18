-- Workspace 跨后端实例的写入租约。Task/Agent 写工具与 Diff commit/push 都必须领取同一把租约，
-- 不使用 JVM 内存锁替代该持久化事实；过期仅用于崩溃恢复接管。
ALTER TABLE workspaces
    ADD COLUMN write_lease_task_id BINARY(16) NULL COMMENT '当前 Workspace 写入租约所属 Task' AFTER status,
    ADD COLUMN write_lease_token VARCHAR(64) NULL COMMENT '写入租约随机令牌' AFTER write_lease_task_id,
    ADD COLUMN write_lease_expires_at DATETIME(6) NULL COMMENT '写入租约 UTC 过期时间' AFTER write_lease_token,
    ADD KEY idx_workspace_write_lease (write_lease_expires_at);

ALTER TABLE workspace_repositories
    ADD COLUMN base_ref VARCHAR(512) NULL COMMENT '创建时固定的不可变基线分支名' AFTER base_commit;

UPDATE workspace_repositories
SET base_ref = base_commit
WHERE base_commit IS NOT NULL
  AND base_commit NOT REGEXP '^[0-9a-fA-F]{40,64}$';