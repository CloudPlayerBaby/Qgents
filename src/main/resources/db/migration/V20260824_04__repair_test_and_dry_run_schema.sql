-- 修复曾错误写入 test_runs 的 Dry Run 重试列。MySQL 8.0.16 不支持 DROP COLUMN IF EXISTS，
-- 因此先从 information_schema 探测，再通过 PREPARE 有条件执行；正确升级链上自动跳过。
SET @test_run_retry_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND column_name = 'retry_of_dry_run_id'
);
SET @test_run_retry_sql = IF(@test_run_retry_column_exists = 1,
    'ALTER TABLE test_runs DROP COLUMN retry_of_dry_run_id', 'SELECT 1');
PREPARE test_run_retry_stmt FROM @test_run_retry_sql;
EXECUTE test_run_retry_stmt;
DEALLOCATE PREPARE test_run_retry_stmt;

SET @test_run_reason_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND column_name = 'retry_reason_code'
);
SET @test_run_reason_sql = IF(@test_run_reason_column_exists = 1,
    'ALTER TABLE test_runs DROP COLUMN retry_reason_code', 'SELECT 1');
PREPARE test_run_reason_stmt FROM @test_run_reason_sql;
EXECUTE test_run_reason_stmt;
DEALLOCATE PREPARE test_run_reason_stmt;

SET @test_run_claim_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'test_runs' AND column_name = 'active_claim_key'
);
SET @test_run_claim_sql = IF(@test_run_claim_column_exists = 1,
    'ALTER TABLE test_runs DROP COLUMN active_claim_key', 'SELECT 1');
PREPARE test_run_claim_stmt FROM @test_run_claim_sql;
EXECUTE test_run_claim_stmt;
DEALLOCATE PREPARE test_run_claim_stmt;
