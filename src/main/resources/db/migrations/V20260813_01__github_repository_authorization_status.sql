-- Migration: Add authorization_status to github_repositories
-- This script safely updates existing databases to include the authorization_status field.

ALTER TABLE github_repositories
    ADD COLUMN authorization_status VARCHAR(32) NOT NULL DEFAULT 'AUTHORIZED' COMMENT '授权状态枚举：AUTHORIZED/REVOKED' AFTER archived;
