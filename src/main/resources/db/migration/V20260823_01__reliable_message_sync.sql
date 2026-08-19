-- 可靠消息同步：通知/团队 SSE 游标在作用域内唯一，配合用户/团队行锁分配。
-- 已执行环境不得改写本迁移；若历史库存在重复序号，需先由运维按 created_at 保留最早记录并修复引用。

CREATE TABLE IF NOT EXISTS push_devices (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    installation_id VARCHAR(128) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    provider VARCHAR(16) NOT NULL DEFAULT 'FCM',
    token_hash CHAR(64) NOT NULL,
    token_ciphertext TEXT NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_push_device_installation (user_id, installation_id),
    UNIQUE KEY uk_push_device_token (provider, token_hash),
    KEY idx_push_device_user_active (user_id, active),
    CONSTRAINT fk_push_device_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_device_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT chk_push_device_provider CHECK (provider IN ('FCM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移动端离线推送设备注册';

CREATE TABLE IF NOT EXISTS push_deliveries (
    id BINARY(16) PRIMARY KEY,
    notification_id BINARY(16) NOT NULL,
    device_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    provider_message_id VARCHAR(255) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    UNIQUE KEY uk_push_delivery_notification_device (notification_id, device_id),
    KEY idx_push_delivery_due (status, next_attempt_at),
    CONSTRAINT fk_push_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT fk_push_delivery_device FOREIGN KEY (device_id) REFERENCES push_devices(id) ON DELETE CASCADE,
    CONSTRAINT chk_push_delivery_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复、幂等的移动端推送投递Outbox';

SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'notification_events' AND index_name = 'uk_ne_recipient_seq'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE notification_events ADD UNIQUE KEY uk_ne_recipient_seq (recipient_user_id, sequence_no)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 唯一索引成功后再删除旧普通索引；若历史数据重复导致创建失败，旧索引仍保留。
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'notification_events' AND index_name = 'idx_ne_recipient_seq'
);
SET @sql = IF(@idx_exists > 0,
              'ALTER TABLE notification_events DROP INDEX idx_ne_recipient_seq',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'team_events' AND index_name = 'uk_te_team_seq'
);
SET @sql = IF(@idx_exists = 0,
              'ALTER TABLE team_events ADD UNIQUE KEY uk_te_team_seq (team_id, sequence_no)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'team_events' AND index_name = 'idx_te_team_seq'
);
SET @sql = IF(@idx_exists > 0,
              'ALTER TABLE team_events DROP INDEX idx_te_team_seq',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
