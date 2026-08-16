-- V20260816_03__team_events.sql
-- 团队级 SSE 事件表（前端 SSE 需求清单 ②：GET /teams/{teamId}/events）:
--   按团队维度持久化事件, sequence_no 为该团队内单调递增游标(Last-Event-ID 续传)。
-- CREATE TABLE IF NOT EXISTS 幂等,可重复执行。

CREATE TABLE IF NOT EXISTS team_events (
    id BINARY(16) PRIMARY KEY COMMENT '事件UUIDv7',
    team_id BINARY(16) NOT NULL COMMENT '所属团队ID',
    sequence_no BIGINT NOT NULL COMMENT '团队内单调递增事件序号（SSE 游标）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（project.member.added/team.member.updated/activity.created）',
    resource_id VARCHAR(64) NULL COMMENT '关联资源ID（projectId 等）',
    payload JSON NULL COMMENT '脱敏事件载荷',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '产生时间（UTC）',
    KEY idx_te_team_seq (team_id, sequence_no),
    CONSTRAINT fk_te_team FOREIGN KEY (team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队级 SSE 事件（团队维度游标）';
