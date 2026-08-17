-- V20260817_02: agents 表 capability 能力标签 改为 description 用途描述
-- 背景：自定义 Agent 模型改为「用户自定义 prompt + description」。description 存该 Agent 是干什么的、
-- 有什么用（展示与选用决策依据，团队可见）；capabilities JSON 能力标签废弃，选用 Agent 时由决策
-- Agent（AgentMatchDecider）依据 role + description 判断，不再按结构化能力标签打分量化。
-- 幂等可重复执行；全新整库初始化时上方 agents 建表已包含 description 列。

-- 1) 补充 description 列（已存在则跳过）
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'description'
);
SET @alter_sql = IF(@col_exists = 0,
    CONCAT('ALTER TABLE agents ADD COLUMN description TEXT NULL COMMENT ''Agent 用途描述'' AFTER avatar'),
    'SELECT 1');
PREPARE desc_stmt FROM @alter_sql;
EXECUTE desc_stmt;
DEALLOCATE PREPARE desc_stmt;

-- 2) 存量默认 Agent 回填用途描述（仅空值，不覆盖用户已填内容）
UPDATE agents SET description = '负责把任务执行进度与最终 Diff 审核卡片回群，不执行具体编码工作'
WHERE role = 'ORCHESTRATOR' AND (description IS NULL OR description = '');
UPDATE agents SET description = '负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检'
WHERE role = 'DEVELOPER' AND (description IS NULL OR description = '');
UPDATE agents SET description = '负责运行测试并依据真实执行结果判定是否满足验收'
WHERE role = 'TESTER' AND (description IS NULL OR description = '');
UPDATE agents SET description = '负责审查本次改动是否符合需求、质量与安全要求'
WHERE role = 'REVIEWER' AND (description IS NULL OR description = '');

-- 3) 删除废弃的能力标签 JSON 列（已存在才删除）
SET @cap_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'capabilities'
);
SET @drop_sql = IF(@cap_exists = 1, 'ALTER TABLE agents DROP COLUMN capabilities', 'SELECT 1');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;
