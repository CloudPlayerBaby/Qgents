-- GitHub OAuth 撤销状态抢占：撤销开始前先原子置为 REVOKING，任何并发建仓因非 ACTIVE 立即被拒。
ALTER TABLE github_user_authorizations
    DROP CHECK chk_ghua_status;
ALTER TABLE github_user_authorizations
    ADD CONSTRAINT chk_ghua_status
        CHECK (status IN ('ACTIVE', 'REVOKING', 'REVOKED', 'EXPIRED', 'ERROR'));
