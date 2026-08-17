-- V20260817_01: 为存量团队幂等补齐 ORCHESTRATOR 编排助手 Agent（任务卡片统一发送者）
-- 背景：任务进度 / Diff 审核卡片改由团队级 ORCHESTRATOR Agent 统一发送。新建团队在
-- 建团队事务内即时预置（TeamService -> OrchestratorAgentService），应用启动预置兜底
-- ACTIVE 团队；本迁移覆盖部署后首次启动前的存量团队，幂等可重复执行。
-- 说明：id 由 UUID() 生成（存量数据回填，非业务主路径）；agents 表对 (team_id, role)
-- 无唯一约束（团队可有多个同角色业务 Agent），靠 NOT EXISTS 保证幂等。

INSERT INTO agents (id, team_id, created_by, name, role, capabilities, prompt, visibility, status)
SELECT UNHEX(REPLACE(UUID(), '-', '')), t.id, t.owner_user_id, '编排助手', 'ORCHESTRATOR',
       JSON_ARRAY('orchestration'),
       '你是 Qgents 的编排助手，负责把任务执行进度与最终 Diff 审核卡片回群，不执行具体编码工作。',
       'TEAM', 'ACTIVE'
FROM teams t
WHERE NOT EXISTS (SELECT 1 FROM agents a
                  WHERE a.team_id = t.id AND a.role = 'ORCHESTRATOR' AND a.status = 'ACTIVE');
