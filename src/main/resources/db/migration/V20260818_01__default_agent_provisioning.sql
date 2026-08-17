-- V20260818_01: 团队默认 Agent 预置改造（每团队每角色至多一条默认 Agent）
-- 背景：默认 Agent 属团队级共享资源，在建团队事务内补齐（DefaultAgentProvisioner）。
-- 本迁移为存量库补结构 + 补数据，幂等可重复执行：
--   1) agents 加 is_default 标记列；
--   2) 存量默认 Agent 按「已知默认角色 + 默认名」打标（避免误伤自定义 Agent）；
--   3) 历史并发已产生的同角色多条默认去重（保留 id 最小，其余降为自定义）——
--      否则唯一索引建不出来；
--   4) 生成列 default_role（仅默认 Agent 暴露 role，自定义为 NULL）+ 唯一索引
--      uk_agents_team_default_role：DB 层保证并发下每团队每角色至多一条默认 Agent；
--   5) 补齐缺失默认角色（含 PLANNER），NOT EXISTS 幂等。
-- 说明：id 由 UUID() 生成（存量数据回填，非业务主路径）；agents 表对 (team_id, role)
-- 无唯一约束（团队可拥有多个同角色业务 Agent），默认集唯一性由 is_default 生成列保证。

-- 1. is_default 标记列（缺失时补）
SET @agent_is_default_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'is_default'
);
SET @agent_is_default_sql = IF(@agent_is_default_exists = 0,
    'ALTER TABLE agents ADD COLUMN is_default TINYINT NOT NULL DEFAULT 0 COMMENT ''是否团队默认 Agent（每团队每角色至多一条）'' AFTER status',
    'SELECT 1');
PREPARE agent_is_default_stmt FROM @agent_is_default_sql;
EXECUTE agent_is_default_stmt;
DEALLOCATE PREPARE agent_is_default_stmt;

-- 2. 存量默认 Agent 打标：已知默认角色 + 默认名（与 DefaultAgentProvisioner 常量对齐）
UPDATE agents SET is_default = 1
WHERE role IN ('PLANNER', 'DEVELOPER', 'TESTER', 'REVIEWER', 'ORCHESTRATOR')
  AND name IN ('规划 Agent', '开发 Agent', '测试 Agent', '审查 Agent', '编排助手');

-- 3. 历史并发残留去重：同团队同角色多条默认，保留 id 最小，其余降为自定义
UPDATE agents a
JOIN (
    SELECT team_id, role, MIN(id) AS keep_id
    FROM agents
    WHERE is_default = 1
    GROUP BY team_id, role
    HAVING COUNT(*) > 1
) dup ON a.team_id = dup.team_id AND a.role = dup.role AND a.id <> dup.keep_id
SET a.is_default = 0;

-- 4. 生成列 + 唯一索引
SET @agent_default_role_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'default_role'
);
SET @agent_default_role_sql = IF(@agent_default_role_exists = 0,
    'ALTER TABLE agents ADD COLUMN default_role VARCHAR(32) GENERATED ALWAYS AS (IF(is_default = 1, role, NULL)) STORED COMMENT ''默认Agent角色（唯一约束生成列）''',
    'SELECT 1');
PREPARE agent_default_role_stmt FROM @agent_default_role_sql;
EXECUTE agent_default_role_stmt;
DEALLOCATE PREPARE agent_default_role_stmt;

SET @agent_default_idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'uk_agents_team_default_role'
);
SET @agent_default_idx_sql = IF(@agent_default_idx_exists = 0,
    'CREATE UNIQUE INDEX uk_agents_team_default_role ON agents (team_id, default_role)',
    'SELECT 1');
PREPARE agent_default_idx_stmt FROM @agent_default_idx_sql;
EXECUTE agent_default_idx_stmt;
DEALLOCATE PREPARE agent_default_idx_stmt;

-- 5. 补齐缺失默认角色（含 PLANNER），幂等（NOT EXISTS 命中默认标记或已知默认名）
INSERT INTO agents (id, team_id, created_by, name, role, description, prompt, visibility, status, is_default)
SELECT UNHEX(REPLACE(UUID(), '-', '')), t.id, t.owner_user_id,
       CASE r.role
           WHEN 'PLANNER' THEN '规划 Agent'
           WHEN 'DEVELOPER' THEN '开发 Agent'
           WHEN 'TESTER' THEN '测试 Agent'
           WHEN 'REVIEWER' THEN '审查 Agent'
       END,
       r.role,
       CASE r.role
           WHEN 'PLANNER' THEN '负责分析需求并制定实现计划，输出可执行、可冻结的实现步骤'
           WHEN 'DEVELOPER' THEN '负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检'
           WHEN 'TESTER' THEN '负责运行测试并依据真实执行结果判定是否满足验收'
           WHEN 'REVIEWER' THEN '负责审查本次改动是否符合需求、质量与安全要求'
       END,
       CASE r.role
           WHEN 'PLANNER' THEN '你是 Qgents 的规划 Agent，请分析需求并制定可执行、可冻结的实现计划。'
           WHEN 'DEVELOPER' THEN '你是 Qgents 的开发 Agent，请按任务要求完成开发阶段的工作。'
           WHEN 'TESTER' THEN '你是 Qgents 的测试 Agent，请按任务要求完成测试阶段的工作。'
           WHEN 'REVIEWER' THEN '你是 Qgents 的审查 Agent，请按任务要求完成审查阶段的工作。'
       END,
       'TEAM', 'ACTIVE', 1
FROM teams t
JOIN (
    SELECT 'PLANNER' AS role
    UNION SELECT 'DEVELOPER'
    UNION SELECT 'TESTER'
    UNION SELECT 'REVIEWER'
) r
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM agents a
      WHERE a.team_id = t.id AND a.role = r.role
        AND (a.is_default = 1 OR a.name IN ('规划 Agent', '开发 Agent', '测试 Agent', '审查 Agent'))
  );