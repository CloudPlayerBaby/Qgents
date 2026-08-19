-- Dry Run 重试必须保留原失败事实，续跑创建新记录并指向来源。
ALTER TABLE dry_runs
    ADD COLUMN retry_of_dry_run_id BINARY(16) NULL COMMENT '不可变重试的来源Dry Run' AFTER attempt_count,
    ADD COLUMN retry_reason_code VARCHAR(128) NULL COMMENT '创建重试时的稳定失败码' AFTER retry_of_dry_run_id,
    ADD KEY idx_dry_run_retry_source (retry_of_dry_run_id, created_at),
    ADD CONSTRAINT fk_dry_run_retry_source FOREIGN KEY (retry_of_dry_run_id) REFERENCES dry_runs (id);
