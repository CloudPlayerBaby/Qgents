-- V20260814_04__task_center_display_fields.sql
-- 任务中心 / 任务详情展示字段:
--   1. tasks.display_code(项目内唯一、创建后不可变的展示编号,如 T-1024);
--   2. task_acceptance_criteria(Task 级验收标准,由 Planner/编排后续写入,前端只读展示)。
-- 全部采用 information_schema 探测 + PREPARE 动态执行实现幂等,对已存在列/表/键的库自动跳过,整体可重复执行。

-- 1. tasks.display_code 列
SET @t_dc_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'display_code'
);
SET @t_dc_alter = IF(@t_dc_exists = 0,
    'ALTER TABLE tasks ADD COLUMN display_code VARCHAR(32) NULL COMMENT ''项目内唯一展示编号，如 T-1024，创建后不可变'' AFTER title',
    'SELECT 1');
PREPARE t_dc_stmt FROM @t_dc_alter;
EXECUTE t_dc_stmt;
DEALLOCATE PREPARE t_dc_stmt;

-- 2. 存量数据回填:按项目内 created_at 顺序编号 T-1..N(仅回填 NULL,幂等)
UPDATE tasks t
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY created_at, id) AS rn
    FROM tasks
) seq ON seq.id = t.id
SET t.display_code = CONCAT('T-', seq.rn)
WHERE t.display_code IS NULL;

-- 3. 项目内唯一键
SET @t_dc_key_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND INDEX_NAME = 'uk_task_display_code'
);
SET @t_dc_key_sql = IF(@t_dc_key_exists = 0,
    'ALTER TABLE tasks ADD UNIQUE KEY uk_task_display_code (project_id, display_code)',
    'SELECT 1');
PREPARE t_dc_key_stmt FROM @t_dc_key_sql;
EXECUTE t_dc_key_stmt;
DEALLOCATE PREPARE t_dc_key_stmt;

-- 4. 回填完成后置 NOT NULL
SET @t_dc_notnull = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks'
       AND COLUMN_NAME = 'display_code' AND IS_NULLABLE = 'YES') > 0,
    'ALTER TABLE tasks MODIFY COLUMN display_code VARCHAR(32) NOT NULL COMMENT ''项目内唯一展示编号，如 T-1024，创建后不可变''',
    'SELECT 1');
PREPARE t_dc_notnull_stmt FROM @t_dc_notnull;
EXECUTE t_dc_notnull_stmt;
DEALLOCATE PREPARE t_dc_notnull_stmt;

-- 5. Task 级验收标准表(契约先行,当前由 Planner/编排后续写入)
CREATE TABLE IF NOT EXISTS task_acceptance_criteria (
    id BINARY(16) PRIMARY KEY COMMENT '验收标准UUIDv7',
    task_id BINARY(16) NOT NULL COMMENT '所属任务ID',
    sequence_no INT UNSIGNED NOT NULL COMMENT '任务内验收标准序号，从1开始',
    title VARCHAR(255) NOT NULL COMMENT '验收标准标题',
    description TEXT NULL COMMENT '验收标准补充说明',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '验收状态：PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间(UTC)',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间(UTC)',
    UNIQUE KEY uk_acceptance_task_sequence (task_id, sequence_no),
    CONSTRAINT fk_acceptance_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CONSTRAINT chk_acceptance_status CHECK (status IN ('PENDING','SATISFIED','UNSATISFIED','NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Task 级验收标准，由 Planner/编排写入，前端只读展示';
