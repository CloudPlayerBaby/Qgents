-- V20260823_02: agents 发布审核字段
-- 背景：自定义 Agent 发布为 TEAM 需 Team Owner 审核。visibility 复用现有列：
--   PRIVATE（默认，仅创建者）→ PENDING（提交审核）→ TEAM（批准）| PRIVATE（拒绝，可重新提交）；
--   TEAM 不可再变回 PRIVATE（仅可归档）。新增审核结果字段，拒绝原因供创建者查看。
SET @agent_review_reason_col = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'review_reason'
);
SET @agent_review_reason_sql = IF(@agent_review_reason_col = 0,
    'ALTER TABLE agents ADD COLUMN review_reason TEXT NULL COMMENT ''发布审核拒绝原因（Team Owner 填写；批准为空）'' AFTER prompt',
    'SELECT 1');
PREPARE agent_review_reason_stmt FROM @agent_review_reason_sql;
EXECUTE agent_review_reason_stmt;
DEALLOCATE PREPARE agent_review_reason_stmt;

SET @agent_reviewed_by_col = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'reviewed_by'
);
SET @agent_reviewed_by_sql = IF(@agent_reviewed_by_col = 0,
    'ALTER TABLE agents ADD COLUMN reviewed_by BINARY(16) NULL COMMENT ''发布审核人（Team Owner）ID'' AFTER review_reason',
    'SELECT 1');
PREPARE agent_reviewed_by_stmt FROM @agent_reviewed_by_sql;
EXECUTE agent_reviewed_by_stmt;
DEALLOCATE PREPARE agent_reviewed_by_stmt;

SET @agent_reviewed_at_col = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'reviewed_at'
);
SET @agent_reviewed_at_sql = IF(@agent_reviewed_at_col = 0,
    'ALTER TABLE agents ADD COLUMN reviewed_at DATETIME(6) NULL COMMENT ''发布审核时间（UTC）'' AFTER reviewed_by',
    'SELECT 1');
PREPARE agent_reviewed_at_stmt FROM @agent_reviewed_at_sql;
EXECUTE agent_reviewed_at_stmt;
DEALLOCATE PREPARE agent_reviewed_at_stmt;

-- 时间戳列：交付中心聚合需要 created_at/updated_at 排序；历史数据用当前时间回填。
SET @agent_created_at_col = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'created_at'
);
SET @agent_created_at_sql = IF(@agent_created_at_col = 0,
    'ALTER TABLE agents ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT ''创建时间（UTC）'' AFTER is_default',
    'SELECT 1');
PREPARE agent_created_at_stmt FROM @agent_created_at_sql;
EXECUTE agent_created_at_stmt;
DEALLOCATE PREPARE agent_created_at_stmt;

SET @agent_updated_at_col = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'updated_at'
);
SET @agent_updated_at_sql = IF(@agent_updated_at_col = 0,
    'ALTER TABLE agents ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT ''更新时间（UTC）'' AFTER created_at',
    'SELECT 1');
PREPARE agent_updated_at_stmt FROM @agent_updated_at_sql;
EXECUTE agent_updated_at_stmt;
DEALLOCATE PREPARE agent_updated_at_stmt;
