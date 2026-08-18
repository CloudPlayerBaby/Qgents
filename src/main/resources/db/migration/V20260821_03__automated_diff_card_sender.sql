-- 团队暂未配置 ORCHESTRATOR Agent 时，仍需以 senderType=SYSTEM 发出可引用的 DIFF 卡。
-- 旧约束仅允许 message_type=SYSTEM 无发送者，会导致 DIFF 卡被跳过，破坏增量续作流程。
SET @old_message_sender_check = (
    SELECT CONSTRAINT_NAME
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'messages'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND CONSTRAINT_NAME <> 'chk_message_sender'
    ORDER BY CONSTRAINT_NAME
    LIMIT 1
);
SET @drop_old_message_sender_check = IF(
    @old_message_sender_check IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE messages DROP CHECK `', @old_message_sender_check, '`')
);
PREPARE drop_old_message_sender_check_stmt FROM @drop_old_message_sender_check;
EXECUTE drop_old_message_sender_check_stmt;
DEALLOCATE PREPARE drop_old_message_sender_check_stmt;

SET @new_message_sender_check_exists = (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'messages'
      AND CONSTRAINT_NAME = 'chk_message_sender'
);
SET @add_message_sender_check = IF(
    @new_message_sender_check_exists = 0,
    'ALTER TABLE messages ADD CONSTRAINT chk_message_sender CHECK ((message_type IN (''SYSTEM'', ''DIFF'', ''TASK_STATUS'') AND author_user_id IS NULL AND agent_id IS NULL) OR ((author_user_id IS NOT NULL OR agent_id IS NOT NULL) AND NOT (author_user_id IS NOT NULL AND agent_id IS NOT NULL)))',
    'SELECT 1'
);
PREPARE add_message_sender_check_stmt FROM @add_message_sender_check;
EXECUTE add_message_sender_check_stmt;
DEALLOCATE PREPARE add_message_sender_check_stmt;
