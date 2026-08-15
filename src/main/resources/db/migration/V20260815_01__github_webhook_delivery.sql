-- GitHub Webhook 投递幂等记录表。
-- 表用 IF NOT EXISTS，可重复执行；已存在列时不做 ALTER，避免破坏已有数据。
CREATE TABLE IF NOT EXISTS github_webhook_deliveries (
    id BINARY(16) PRIMARY KEY COMMENT '投递记录UUIDv7',
    provider_delivery_id VARCHAR(64) NOT NULL COMMENT 'X-GitHub-Delivery，GitHub 投递唯一标识',
    event_name VARCHAR(64) NOT NULL COMMENT 'X-GitHub-Event 事件名',
    action VARCHAR(64) NULL COMMENT 'payload 中的 action',
    provider_installation_id BIGINT UNSIGNED NULL COMMENT 'GitHub installation.id，可空',
    provider_repository_id BIGINT UNSIGNED NULL COMMENT 'GitHub repository.id，可空',
    payload_sha256 CHAR(64) NOT NULL COMMENT '原始 body SHA-256 摘要，不保存 Secret',
    status VARCHAR(32) NOT NULL COMMENT 'RECEIVED/PROCESSED/IGNORED/FAILED',
    failure_code VARCHAR(128) NULL COMMENT '最近失败码，不写入 Secret 或完整 payload',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同一 delivery 实际处理次数',
    received_at DATETIME(6) NOT NULL COMMENT '接收时间（UTC）',
    processed_at DATETIME(6) NULL COMMENT '处理完成时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '最近更新时间（UTC）',
    UNIQUE KEY uk_ghwd_delivery (provider_delivery_id),
    KEY idx_ghwd_status (status, received_at),
    KEY idx_ghwd_install (provider_installation_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GitHub Webhook 投递幂等记录';
