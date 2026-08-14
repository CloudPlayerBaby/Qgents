ALTER TABLE tasks
    ADD COLUMN delivery_mode VARCHAR(32) NOT NULL DEFAULT 'DIFF_FIRST' COMMENT 'DIFF_FIRST/MR_FIRST';

CREATE TABLE IF NOT EXISTS task_execution_artifacts (
    id BINARY(16) PRIMARY KEY,
    task_id BINARY(16) NOT NULL,
    task_run_id BINARY(16) NULL,
    task_step_id BINARY(16) NULL,
    sequence_no INT NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    summary JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_artifact_task_sequence (task_id, sequence_no),
    KEY idx_artifact_run (task_run_id),
    CONSTRAINT fk_artifact_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_run FOREIGN KEY (task_run_id) REFERENCES task_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_step FOREIGN KEY (task_step_id) REFERENCES task_steps(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS diff_review_batches (
    id BINARY(16) PRIMARY KEY,
    project_id BINARY(16) NOT NULL,
    task_id BINARY(16) NOT NULL,
    workspace_id BINARY(16) NOT NULL,
    final_coding_task_run_id BINARY(16) NOT NULL,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    aggregate_hash VARCHAR(256) NOT NULL,
    reviewed_by BINARY(16) NULL,
    review_reason TEXT NULL,
    reviewed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_diff_batch_task_run (task_id, final_coding_task_run_id),
    KEY idx_diff_batch_project (project_id),
    CONSTRAINT fk_diff_batch_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_diff_batch_task FOREIGN KEY (task_id) REFERENCES tasks(id),
    CONSTRAINT fk_diff_batch_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_diff_batch_run FOREIGN KEY (final_coding_task_run_id) REFERENCES task_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE diffs
    ADD COLUMN review_batch_id BINARY(16) NULL,
    ADD COLUMN delivery_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN delivery_failure_reason TEXT NULL,
    ADD KEY idx_diff_review_batch (review_batch_id),
    ADD CONSTRAINT fk_diff_batch FOREIGN KEY (review_batch_id) REFERENCES diff_review_batches(id);
