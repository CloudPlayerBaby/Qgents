-- MR 评论：普通评论同步到 GitHub Issue Comment，行级评论同步到 Pull Request Review Comment。
CREATE TABLE IF NOT EXISTS
    merge_request_comments (
        id BINARY(16) PRIMARY KEY COMMENT 'MR评论UUIDv7',
        merge_request_id BINARY(16) NOT NULL COMMENT '所属MR ID',
        author_user_id BINARY(16) NOT NULL COMMENT 'Qgents评论作者',
        provider_comment_id VARCHAR(255) NOT NULL COMMENT 'GitHub评论ID',
        body TEXT NOT NULL COMMENT '评论正文',
        web_url VARCHAR(2000) NULL COMMENT 'GitHub评论地址',
        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
        UNIQUE KEY uk_mr_provider_comment (merge_request_id, provider_comment_id),
        KEY idx_mr_comment_created (merge_request_id, created_at),
        CONSTRAINT fk_mr_comment_mr FOREIGN KEY (merge_request_id) REFERENCES merge_requests (id) ON DELETE CASCADE,
        CONSTRAINT fk_mr_comment_author FOREIGN KEY (author_user_id) REFERENCES users (id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Qgents创建的GitHub MR普通评论镜像';
