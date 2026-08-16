-- V20260816_02__notification_events.sql
-- 通知级 SSE 事件表（前端 SSE 需求清单 ③：GET /notifications/events）:
--   按用户维度持久化通知事件, sequence_no 为该用户内单调递增游标(Last-Event-ID 续传)。
-- CREATE TABLE IF NOT EXISTS 幂等,可重复执行。

CREATE TABLE IF NOT EXISTS notification_events (
    id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
    recipient_user_id BINARY(16) NOT NULL COMMENT '接收通知的用户ID',
    sequence_no BIGINT NOT NULL COMMENT '用户内单调递增事件序号（SSE 游标）',
    notification_id BINARY(16) NULL COMMENT '关联通知ID',
    kind VARCHAR(32) NULL COMMENT '通知类型（TASK_COMPLETED/INVITED 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    KEY idx_ne_recipient_seq (recipient_user_id, sequence_no),
    CONSTRAINT fk_ne_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知级 SSE 事件（用户维度游标）';
