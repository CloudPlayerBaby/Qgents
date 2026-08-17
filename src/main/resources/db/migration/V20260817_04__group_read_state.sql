-- V20260817_04: 群成员已读游标表（未读数=群最新sequence-已读游标，排除本人）
-- 背景：群聊未读数从纯前端 localStorage 兜底升级为后端权威状态。按「用户×群」持久化已读游标，
-- 未读数 = 该群消息 sequence_no > 已读游标 且 非本人 的消息数。游标只前进不后退（进群全读语义）。
-- 幂等可重复执行；全新整库初始化时上方建表脚本已包含本表。
CREATE TABLE IF NOT EXISTS group_read_state (
    user_id BINARY(16) NOT NULL COMMENT '用户ID',
    group_id BINARY(16) NOT NULL COMMENT '需求群ID',
    last_read_sequence_no BIGINT UNSIGNED NULL COMMENT '已读游标（群内消息序号，NULL 视为 0）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (user_id, group_id),
    KEY idx_grs_group (group_id),
    CONSTRAINT fk_grs_group FOREIGN KEY (group_id) REFERENCES requirement_groups (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群成员已读游标（未读数=最新sequence-游标，排除本人）';
