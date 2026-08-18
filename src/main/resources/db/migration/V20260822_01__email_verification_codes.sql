-- 注册邮箱验证码：注册前先发送 6 位数字验证码，注册时校验通过才创建账号。
-- 验证码仅存 SHA-256 哈希，禁止存储明文；每条记录一次性使用，可重复请求（旧记录失效）。
CREATE TABLE IF NOT EXISTS email_verification_codes (
    id BINARY(16) PRIMARY KEY COMMENT '验证码记录UUIDv7',
    email VARCHAR(320) NOT NULL COMMENT '归一化邮箱（小写）',
    code_hash BINARY(32) NOT NULL COMMENT '验证码SHA-256哈希，禁止存储明文',
    expires_at DATETIME(6) NOT NULL COMMENT '验证码过期时间（UTC）',
    used_at DATETIME(6) NULL COMMENT '验证码使用时间（UTC），为空表示未使用',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发送时间（UTC）',
    UNIQUE KEY uk_verify_hash (code_hash),
    KEY idx_verify_email (email, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '注册邮箱验证码一次性记录';
