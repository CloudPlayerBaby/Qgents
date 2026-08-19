-- V20260823_03: agents.visibility 支持 PENDING（发布审核态）
-- 背景：自定义 Agent 发布为 TEAM 需 Team Owner 审核。visibility 新增 PENDING（待审核），
-- 原有 CHECK 约束只允许 TEAM/PRIVATE，需先删旧约束再加新约束；MySQL 8.0 不支持
-- ALTER TABLE ... DROP CHECK IF EXISTS 的幂等语法，用 information_schema 判断后动态执行。
SET @agent_check_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'agents'
      AND CONSTRAINT_TYPE = 'CHECK' AND CONSTRAINT_NAME = 'ck_agent_visibility'
);
SET @agent_drop_check_sql = IF(@agent_check_exists = 1,
    'ALTER TABLE agents DROP CHECK ck_agent_visibility',
    'SELECT 1');
PREPARE agent_drop_check_stmt FROM @agent_drop_check_sql;
EXECUTE agent_drop_check_stmt;
DEALLOCATE PREPARE agent_drop_check_stmt;

SET @agent_new_check_sql = IF(@agent_check_exists = 1,
    'ALTER TABLE agents ADD CONSTRAINT ck_agent_visibility CHECK(visibility IN (''TEAM'',''PENDING'',''PRIVATE''))',
    'SELECT 1');
PREPARE agent_new_check_stmt FROM @agent_new_check_sql;
EXECUTE agent_new_check_stmt;
DEALLOCATE PREPARE agent_new_check_stmt;
