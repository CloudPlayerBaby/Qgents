-- 通知中心：服务器既有库迁移脚本（A 联调约定 §1）。
-- 适用于已部署过旧版 schema 的数据库，仅需执行一次；全新库由 qgents_schema.sql 全量初始化覆盖。
-- 新表使用 CREATE TABLE IF NOT EXISTS，脚本整体可重复执行。

CREATE TABLE IF NOT EXISTS
    notifications (
        id BINARY(16) PRIMARY KEY COMMENT '通知UUIDv7',
        recipient_user_id BINARY(16) NOT NULL COMMENT '接收通知的用户ID',
        project_id BINARY(16) NULL COMMENT '关联项目ID；系统级通知为空',
        requirement_group_id BINARY(16) NULL COMMENT '关联需求群ID；非群通知为空',
        kind VARCHAR(64) NOT NULL COMMENT '通知类型枚举：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING',
        title VARCHAR(255) NOT NULL COMMENT '一行通知标题',
        description TEXT NULL COMMENT '通知描述正文',
        resource_id VARCHAR(128) NULL COMMENT '关联资源ID字符串，如taskId/mrId/diffId',
        is_read TINYINT (1) NOT NULL DEFAULT 0 COMMENT '是否已读：0未读1已读',
        created_at DATETIME (6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
        read_at DATETIME (6) NULL COMMENT '已读时间（UTC），未读为空',
        KEY idx_notif_user (recipient_user_id, is_read, created_at),
        KEY idx_notif_project (project_id),
        KEY idx_notif_group (requirement_group_id),
        CONSTRAINT fk_notif_user FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
        CONSTRAINT fk_notif_project FOREIGN KEY (project_id) REFERENCES projects (id),
        CONSTRAINT fk_notif_group FOREIGN KEY (requirement_group_id) REFERENCES requirement_groups (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户通知中心';
