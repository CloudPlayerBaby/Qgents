-- V20260816_01__project_settings.sql
-- 项目设置（需求群规则开关）持久化:
--   projects.settings JSON 列,存 4 个需求群规则开关(成员B 后端接口补充清单 §二):
--     allowCreateGroup / autoArchiveGroup / allowAgentTrigger / autoJoinAllGroups
-- 采用 information_schema 探测 + PREPARE 幂等,对已存在列的库自动跳过,可重复执行。

SET @ps_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'settings'
);
SET @ps_alter = IF(@ps_exists = 0,
    'ALTER TABLE projects ADD COLUMN settings JSON NULL COMMENT ''项目设置：需求群规则开关 JSON'' AFTER description',
    'SELECT 1');
PREPARE ps_stmt FROM @ps_alter;
EXECUTE ps_stmt;
DEALLOCATE PREPARE ps_stmt;

-- 存量行回填默认设置(仅 NULL,幂等)
UPDATE projects
SET settings = JSON_OBJECT(
    'allowCreateGroup', TRUE,
    'autoArchiveGroup', FALSE,
    'allowAgentTrigger', TRUE,
    'autoJoinAllGroups', FALSE
)
WHERE settings IS NULL;
