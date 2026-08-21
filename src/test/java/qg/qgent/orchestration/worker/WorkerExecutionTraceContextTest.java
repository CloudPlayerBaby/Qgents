package qg.qgent.orchestration.worker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Worker 工具摘要按 TaskRun 隔离并在读取后清理。 */
class WorkerExecutionTraceContextTest {

    @Test
    void recordsExecutionForCurrentTaskRunAndDrainsOnce() {
        UUID taskRunId = UUID.randomUUID();
        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setTool("file.patch");
        execution.setStatus("FAILED");
        execution.setFailureReason("FILE_PATCH_FAILED: 补丁上下文不一致");

        WorkerExecutionTraceContext.begin(taskRunId);
        try {
            WorkerExecutionTraceContext.record(execution);
        } finally {
            WorkerExecutionTraceContext.detach();
        }

        assertThat(WorkerExecutionTraceContext.drain(taskRunId)).containsExactly(execution);
        assertThat(WorkerExecutionTraceContext.drain(taskRunId)).isEmpty();
    }

    @Test
    void doesNotRecordWithoutTaskRunScope() {
        UUID unrelatedRunId = UUID.randomUUID();
        WorkerExecutionTraceContext.detach();

        WorkerExecutionTraceContext.record(new WorkerToolExecution());

        assertThat(WorkerExecutionTraceContext.drain(unrelatedRunId)).isEmpty();
    }

    @Test
    void terminalExecutionReplacesSubmittedSummaryForSameId() {
        UUID taskRunId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        WorkerToolExecution submitted = new WorkerToolExecution();
        submitted.setId(executionId);
        submitted.setTool("development.run");
        submitted.setStatus("QUEUED");
        WorkerToolExecution completed = new WorkerToolExecution();
        completed.setId(executionId);
        completed.setTool("development.run");
        completed.setStatus("FAILED");
        completed.setExitCode(127);

        WorkerExecutionTraceContext.begin(taskRunId);
        try {
            WorkerExecutionTraceContext.record(submitted);
            WorkerExecutionTraceContext.record(completed);
        } finally {
            WorkerExecutionTraceContext.detach();
        }

        assertThat(WorkerExecutionTraceContext.drain(taskRunId)).containsExactly(completed);
    }
}
