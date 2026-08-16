-- V20260816_02__merge_request_mergeability.sql
-- MR 冲突检测：merge_requests 增加 GitHub mergeable / mergeable_state / base_sha 三列，
-- 用于创建后自动轮询、sync 手动刷新与合并前拦截（MR_HAS_CONFLICTS）。
-- 采用 information_schema 探测 + PREPARE 幂等，对已存在列的库自动跳过，可重复执行。

SET @mr_mg_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests' AND COLUMN_NAME = 'mergeable'
);
SET @mr_mg_alter = IF(@mr_mg_exists = 0,
    'ALTER TABLE merge_requests ADD COLUMN mergeable TINYINT NULL COMMENT ''GitHub是否可合并；null表示GitHub尚未计算完成'' AFTER head_commit',
    'SELECT 1');
PREPARE mr_mg_stmt FROM @mr_mg_alter;
EXECUTE mr_mg_stmt;
DEALLOCATE PREPARE mr_mg_stmt;

SET @mr_ms_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests' AND COLUMN_NAME = 'mergeable_state'
);
SET @mr_ms_alter = IF(@mr_ms_exists = 0,
    'ALTER TABLE merge_requests ADD COLUMN mergeable_state VARCHAR(32) NULL COMMENT ''GitHub mergeable_state枚举：clean/dirty/blocked/behind/unstable/draft/unknown'' AFTER mergeable',
    'SELECT 1');
PREPARE mr_ms_stmt FROM @mr_ms_alter;
EXECUTE mr_ms_stmt;
DEALLOCATE PREPARE mr_ms_stmt;

SET @mr_bs_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merge_requests' AND COLUMN_NAME = 'base_sha'
);
SET @mr_bs_alter = IF(@mr_bs_exists = 0,
    'ALTER TABLE merge_requests ADD COLUMN base_sha VARCHAR(128) NULL COMMENT ''合并基线（目标分支）提交SHA'' AFTER mergeable_state',
    'SELECT 1');
PREPARE mr_bs_stmt FROM @mr_bs_alter;
EXECUTE mr_bs_stmt;
DEALLOCATE PREPARE mr_bs_stmt;
