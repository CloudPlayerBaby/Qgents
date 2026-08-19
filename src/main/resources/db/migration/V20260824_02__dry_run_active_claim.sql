-- 终态完成时 active_claim_key 清空；唯一键只阻止同一上下文的并发 QUEUED/RUNNING 受理。
ALTER TABLE dry_runs
    ADD COLUMN active_claim_key VARCHAR(1500) NULL COMMENT 'QUEUED/RUNNING 上下文并发声明' AFTER retry_reason_code,
    ADD UNIQUE KEY uk_dry_run_active_claim (active_claim_key(255));
