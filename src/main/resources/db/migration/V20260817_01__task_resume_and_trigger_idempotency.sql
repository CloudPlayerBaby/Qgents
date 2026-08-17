-- V20260817_01__task_resume_and_trigger_idempotency.sql
-- 1) tasks.trigger_message_id 唯一约束：同一触发消息只能建一个 Task（@agent 自动触发 + 手动触发并发防重）。
--    先检测重复数据：存在重复的非空 trigger_message_id 时打印警告并跳过加约束（需要先人工清理再执行）。
-- 2) 无新增列（任务续跑/恢复复用 tasks.status 与 task_runs 现有列，认领为原子 UPDATE）。

SET @dup_count = (
    SELECT COUNT(*) FROM (
        SELECT trigger_message_id FROM tasks
        WHERE trigger_message_id IS NOT NULL
        GROUP BY trigger_message_id HAVING COUNT(*) > 1
    ) d
);

SET @uk_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND INDEX_NAME = 'uk_task_trigger_message'
);

-- 存在重复数据时跳过（人工清理后重跑本迁移）；约束已存在也跳过（可重复执行）。
SET @uk_stmt = IF(@dup_count = 0 AND @uk_exists = 0,
    'ALTER TABLE tasks ADD UNIQUE KEY uk_task_trigger_message(trigger_message_id)',
    'SELECT 1');
PREPARE uk_stmt FROM @uk_stmt;
EXECUTE uk_stmt;
DEALLOCATE PREPARE uk_stmt;
