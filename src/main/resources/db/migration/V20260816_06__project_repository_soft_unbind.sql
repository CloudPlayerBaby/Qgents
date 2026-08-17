-- ============================================================
-- GitHub 仓库软解绑（方案 B）：project_repositories 增加绑定状态。
--
-- 软解绑不物理删除 project_repositories 历史记录，仅标记 status=UNBOUND，
-- 保留 RequirementGroup / Task / Workspace / Diff / MR 等下游外键引用。
-- 已有记录通过 DEFAULT 'ACTIVE' 迁移为 ACTIVE。
--
-- 执行方式（云端已存在的库，走增量脚本）：
--   mysql -u <user> -p <database> < V20260816_06__project_repository_soft_unbind.sql
--
-- 对应的全量初始化语句已同步维护在 db/qgents_schema.sql。
-- ============================================================

ALTER TABLE project_repositories
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '绑定状态：ACTIVE/UNBOUND（软解绑）' AFTER bound_at,
    ADD COLUMN unbound_at DATETIME(6) NULL
        COMMENT '软解绑时间（UTC）' AFTER status;

-- 状态枚举约束（MySQL 8.0.16+ 支持 CHECK）
ALTER TABLE project_repositories
    ADD CONSTRAINT chk_pr_status CHECK (status IN ('ACTIVE', 'UNBOUND'));
