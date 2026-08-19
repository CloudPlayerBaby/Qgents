ALTER TABLE tool_executions
    ADD COLUMN failure_code VARCHAR(64) NULL COMMENT '稳定失败码' AFTER result_json;
