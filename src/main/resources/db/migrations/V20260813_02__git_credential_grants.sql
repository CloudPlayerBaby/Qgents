CREATE TABLE IF NOT EXISTS git_credential_grants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    grant_id_hash VARCHAR(64) NOT NULL UNIQUE,
    team_id BINARY(16) NOT NULL,
    project_id BINARY(16) NOT NULL,
    installation_id BIGINT UNSIGNED NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    expected_head_commit VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    is_used TINYINT(1) DEFAULT 0 NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_grant_hash (grant_id_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Git 一次性临时凭据授权表';
