ALTER TABLE test_runs
    ADD COLUMN execution_snapshot JSON NULL COMMENT '创建时固化的不可变 Testset 执行定义' AFTER testset_ids,
    ADD COLUMN execution_source_ref VARCHAR(512) NULL COMMENT 'Worker 使用的固定 Git commit SHA' AFTER execution_snapshot,
    ADD COLUMN execution_workspace_id BINARY(16) NULL COMMENT 'Task 工作树的隔离测试 Workspace ID' AFTER execution_source_ref,
    ADD COLUMN claim_token VARCHAR(64) NULL COMMENT '多实例原子领取令牌' AFTER summary,
    ADD COLUMN lease_expires_at DATETIME(6) NULL COMMENT '执行租约到期时间 UTC' AFTER claim_token,
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '执行领取次数' AFTER lease_expires_at,
    ADD KEY idx_test_run_recovery (status, lease_expires_at, created_at),
    ADD KEY idx_test_run_cleanup (status, execution_workspace_id, updated_at);

ALTER TABLE dry_runs
    ADD COLUMN source_ref VARCHAR(512) NULL COMMENT '创建请求的源分支或提交引用' AFTER project_repository_id,
    MODIFY COLUMN head_commit VARCHAR(128) NULL COMMENT '受理时由 Worker 解析并固定的源提交 SHA',
    ADD COLUMN resolved_target_commit VARCHAR(128) NULL COMMENT '受理时由 Worker 解析并固定的目标提交 SHA' AFTER head_commit,
    ADD COLUMN testset_snapshot JSON NULL COMMENT '目标分支门禁 Testset 不可变执行快照' AFTER report,
    ADD COLUMN claim_token VARCHAR(64) NULL COMMENT '多实例原子领取令牌' AFTER testset_snapshot,
    ADD COLUMN lease_expires_at DATETIME(6) NULL COMMENT '执行租约到期时间 UTC' AFTER claim_token,
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '执行领取次数' AFTER lease_expires_at,
    ADD KEY idx_dry_run_recovery (status, lease_expires_at, created_at);

-- 历史运行没有完整快照或固定 SHA，不能安全重放。先依据旧状态写入失败结果，
-- 再更新状态，避免依赖 MySQL 同一 UPDATE 中从左到右的赋值顺序。
UPDATE test_runs
SET summary = JSON_OBJECT('failureCode', 'LEGACY_EXECUTION_SNAPSHOT_MISSING')
WHERE status IN ('QUEUED', 'RUNNING')
  AND (execution_snapshot IS NULL OR execution_source_ref IS NULL);

UPDATE test_runs
SET status = 'FAILED'
WHERE status IN ('QUEUED', 'RUNNING')
  AND (execution_snapshot IS NULL OR execution_source_ref IS NULL);

UPDATE test_runs
SET execution_snapshot = COALESCE(execution_snapshot, JSON_ARRAY()),
    execution_source_ref = COALESCE(execution_source_ref, NULLIF(ref, ''), 'legacy-unavailable')
WHERE execution_snapshot IS NULL OR execution_source_ref IS NULL;

UPDATE dry_runs
SET report = JSON_OBJECT('failureCode', 'LEGACY_EXECUTION_SNAPSHOT_MISSING')
WHERE status IN ('QUEUED', 'RUNNING')
  AND (source_ref IS NULL OR head_commit IS NULL OR resolved_target_commit IS NULL OR testset_snapshot IS NULL);

UPDATE dry_runs
SET status = 'FAILED'
WHERE status IN ('QUEUED', 'RUNNING')
  AND (source_ref IS NULL OR head_commit IS NULL OR resolved_target_commit IS NULL OR testset_snapshot IS NULL);

UPDATE dry_runs
SET source_ref = COALESCE(source_ref, NULLIF(head_commit, ''), 'legacy-unavailable'),
    head_commit = COALESCE(head_commit, 'legacy-unavailable'),
    resolved_target_commit = COALESCE(resolved_target_commit, 'legacy-unavailable'),
    testset_snapshot = COALESCE(testset_snapshot, JSON_ARRAY())
WHERE source_ref IS NULL OR head_commit IS NULL OR resolved_target_commit IS NULL OR testset_snapshot IS NULL;

ALTER TABLE test_runs
    MODIFY COLUMN execution_snapshot JSON NOT NULL COMMENT '创建时固化的不可变 Testset 执行定义',
    MODIFY COLUMN execution_source_ref VARCHAR(512) NOT NULL COMMENT 'Worker 使用的固定 Git commit SHA';

ALTER TABLE dry_runs
    MODIFY COLUMN source_ref VARCHAR(512) NOT NULL COMMENT '创建请求的源分支或提交引用',
    MODIFY COLUMN head_commit VARCHAR(128) NOT NULL COMMENT '受理时由 Worker 解析并固定的源提交 SHA',
    MODIFY COLUMN resolved_target_commit VARCHAR(128) NOT NULL COMMENT '受理时由 Worker 解析并固定的目标提交 SHA',
    MODIFY COLUMN testset_snapshot JSON NOT NULL COMMENT '目标分支门禁 Testset 不可变执行快照';

ALTER TABLE diffs
    ADD COLUMN delivery_failure_code VARCHAR(128) NULL COMMENT '稳定的交付失败分类码' AFTER delivery_status,
    ADD COLUMN delivery_operation_id VARCHAR(64) NULL COMMENT '受控 Commit 幂等操作 ID' AFTER delivery_failure_reason,
    ADD COLUMN delivery_claim_token VARCHAR(64) NULL COMMENT '当前非批次交付 fencing token' AFTER delivery_operation_id,
    ADD COLUMN delivery_lease_expires_at DATETIME(6) NULL COMMENT '非批次 Diff 交付租约到期时间 UTC' AFTER delivery_claim_token;

ALTER TABLE diff_review_batches
    ADD COLUMN delivery_operation_id VARCHAR(64) NULL COMMENT '批次交付幂等操作 ID' AFTER delivery_status,
    ADD COLUMN delivery_claim_token VARCHAR(64) NULL COMMENT '当前批次交付 fencing token' AFTER delivery_operation_id,
    ADD COLUMN delivery_lease_expires_at DATETIME(6) NULL COMMENT '批次交付租约到期时间 UTC' AFTER delivery_claim_token,
    ADD KEY idx_diff_batch_recovery (delivery_status, delivery_lease_expires_at);
