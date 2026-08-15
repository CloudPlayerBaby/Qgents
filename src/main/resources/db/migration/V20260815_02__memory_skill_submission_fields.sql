-- V20260815_01__memory_skill_submission_fields.sql
-- Memory/Skill 提交审核信息持久化（成员B 最终对接契约 §三）:
--   1. memories.submitted_by / submitted_at(最近一次提交审核的申请人/时间,供 DeliveryCenter submitter/submittedAt 返回);
--   2. skills.submitted_by / submitted_at(同上)。
-- 全部采用 information_schema 探测 + PREPARE 动态执行实现幂等,对已存在列的库自动跳过,整体可重复执行。

-- 1. memories.submitted_by 列
SET @m_sb_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memories' AND COLUMN_NAME = 'submitted_by'
);
SET @m_sb_alter = IF(@m_sb_exists = 0,
    'ALTER TABLE memories ADD COLUMN submitted_by BINARY(16) NULL COMMENT ''最近提交审核用户ID'' AFTER status',
    'SELECT 1');
PREPARE m_sb_stmt FROM @m_sb_alter;
EXECUTE m_sb_stmt;
DEALLOCATE PREPARE m_sb_stmt;

-- 2. memories.submitted_at 列
SET @m_sa_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memories' AND COLUMN_NAME = 'submitted_at'
);
SET @m_sa_alter = IF(@m_sa_exists = 0,
    'ALTER TABLE memories ADD COLUMN submitted_at DATETIME(6) NULL COMMENT ''最近提交审核时间（UTC）'' AFTER submitted_by',
    'SELECT 1');
PREPARE m_sa_stmt FROM @m_sa_alter;
EXECUTE m_sa_stmt;
DEALLOCATE PREPARE m_sa_stmt;

-- 3. memories.submitted_by 外键
SET @m_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memories' AND CONSTRAINT_NAME = 'fk_memory_submitter'
);
SET @m_fk_alter = IF(@m_fk_exists = 0,
    'ALTER TABLE memories ADD CONSTRAINT fk_memory_submitter FOREIGN KEY (submitted_by) REFERENCES users(id)',
    'SELECT 1');
PREPARE m_fk_stmt FROM @m_fk_alter;
EXECUTE m_fk_stmt;
DEALLOCATE PREPARE m_fk_stmt;

-- 4. skills.submitted_by 列
SET @s_sb_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skills' AND COLUMN_NAME = 'submitted_by'
);
SET @s_sb_alter = IF(@s_sb_exists = 0,
    'ALTER TABLE skills ADD COLUMN submitted_by BINARY(16) NULL COMMENT ''最近提交审核用户ID'' AFTER status',
    'SELECT 1');
PREPARE s_sb_stmt FROM @s_sb_alter;
EXECUTE s_sb_stmt;
DEALLOCATE PREPARE s_sb_stmt;

-- 5. skills.submitted_at 列
SET @s_sa_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skills' AND COLUMN_NAME = 'submitted_at'
);
SET @s_sa_alter = IF(@s_sa_exists = 0,
    'ALTER TABLE skills ADD COLUMN submitted_at DATETIME(6) NULL COMMENT ''最近提交审核时间（UTC）'' AFTER submitted_by',
    'SELECT 1');
PREPARE s_sa_stmt FROM @s_sa_alter;
EXECUTE s_sa_stmt;
DEALLOCATE PREPARE s_sa_stmt;

-- 6. skills.submitted_by 外键
SET @s_fk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skills' AND CONSTRAINT_NAME = 'fk_skill_submitter'
);
SET @s_fk_alter = IF(@s_fk_exists = 0,
    'ALTER TABLE skills ADD CONSTRAINT fk_skill_submitter FOREIGN KEY (submitted_by) REFERENCES users(id)',
    'SELECT 1');
PREPARE s_fk_stmt FROM @s_fk_alter;
EXECUTE s_fk_stmt;
DEALLOCATE PREPARE s_fk_stmt;
