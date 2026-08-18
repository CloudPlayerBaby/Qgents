-- workspace_repositories.base_commit 语义拆分：base_commit 专存 provision 后解析的真实 SHA，
-- 新增不可变 base_ref 存创建时指定的基线分支名。存量行中 base_commit 仍为分支名（未 provision）
-- 的回填到 base_ref；已是 SHA 的行 base_ref 留空，由兼容回退逻辑处理。
ALTER TABLE workspace_repositories
    ADD COLUMN base_ref VARCHAR(512) NULL COMMENT '创建时固定的不可变基线分支名' AFTER base_commit;

UPDATE workspace_repositories
SET base_ref = base_commit
WHERE base_commit IS NOT NULL
  AND base_commit NOT REGEXP '^[0-9a-fA-F]{40,64}$';
