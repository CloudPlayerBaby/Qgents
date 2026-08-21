-- GitHub OAuth 用户授权：独立于团队 GitHub App Installation，不保存明文 Token。
CREATE TABLE IF NOT EXISTS github_oauth_states (
    id BINARY(16) PRIMARY KEY,
    state_hash BINARY(32) NOT NULL,
    user_id BINARY(16) NOT NULL,
    client VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ghos_state_hash (state_hash),
    KEY idx_ghos_user (user_id, created_at),
    KEY idx_ghos_expiry (expires_at, consumed_at),
    CONSTRAINT fk_ghos_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_ghos_client CHECK (client IN ('WEB', 'MOBILE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GitHub OAuth 一次性 state 摘要';

CREATE TABLE IF NOT EXISTS github_user_authorizations (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_user_id BIGINT UNSIGNED NOT NULL,
    provider_login VARCHAR(255) NOT NULL,
    access_token_ciphertext TEXT NULL,
    scopes VARCHAR(1024) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    authorized_at DATETIME(6) NOT NULL,
    last_validated_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ghua_user_provider (user_id, provider),
    UNIQUE KEY uk_ghua_provider_user (provider, provider_user_id),
    KEY idx_ghua_user_status (user_id, status),
    KEY idx_ghua_provider_status (provider_user_id, status),
    CONSTRAINT fk_ghua_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_ghua_provider CHECK (provider IN ('GITHUB')),
    CONSTRAINT chk_ghua_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Qgents 用户 GitHub OAuth 授权密文';
