-- 需求群显式成员管理（前端契约 2026-08-17「群成员选择与管理」）
-- 群成员从「全部项目成员」解耦为可管理的显式关系：
--   REQUIREMENT 需求群成员 = group_members（用户）+ group_agents（Agent）；
--   PROJECT_MAIN 主群不写入本表，成员恒为全部项目成员（系统管理，不提供成员管理接口）。

CREATE TABLE IF NOT EXISTS group_members (
    requirement_group_id BINARY(16) NOT NULL COMMENT '需求群ID',
    user_id BINARY(16) NOT NULL COMMENT '项目成员用户ID',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
    PRIMARY KEY (requirement_group_id, user_id),
    KEY idx_gm_user (user_id),
    CONSTRAINT fk_gm_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_gm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求群显式成员（用户）；PROJECT_MAIN 主群不写入，成员恒为全部项目成员';

-- 存量迁移：既有 REQUIREMENT 需求群默认纳入全部项目成员（保持「默认全员」现状），
-- 之后可通过邀请/移出接口调整；PROJECT_MAIN 主群不写入（恒为全部项目成员）。
-- 注意：ON DUPLICATE KEY UPDATE 中 joined_at 必须限定目标表——SELECT 源表 project_members
-- 也含 joined_at 列，不限定会报 MySQL 1052 Column ambiguous。
INSERT INTO group_members (requirement_group_id, user_id, joined_at)
SELECT g.id, pm.user_id, CURRENT_TIMESTAMP(6)
FROM requirement_groups g
JOIN project_members pm ON pm.project_id = g.project_id
WHERE g.group_type = 'REQUIREMENT'
ON DUPLICATE KEY UPDATE joined_at = group_members.joined_at;
