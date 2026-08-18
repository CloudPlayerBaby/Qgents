-- 普通 Test Run 先受理，再由异步执行器将分支/标签解析为固定提交；Task 测试仍在受理时固定提交。
ALTER TABLE test_runs
    MODIFY COLUMN execution_source_ref VARCHAR(512) NOT NULL
        COMMENT 'Task测试的固定提交，或普通测试待异步解析的Git引用';
