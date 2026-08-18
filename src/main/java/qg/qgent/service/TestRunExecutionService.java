package qg.qgent.service;

import org.springframework.stereotype.Service;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.orchestration.worker.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 通过数据库租约领取 TestRun/DryRun，支持多实例并发与进程重启恢复。
 */
@Service
public class TestRunExecutionService {
    private static final Duration LEASE_MARGIN = Duration.ofMinutes(10);
    private final TestRunMapper testRuns;
    private final DryRunMapper dryRuns;
    private final SandboxWorkerClient worker;
    private final EventService events;
    private final TaskMapper tasks;

    public TestRunExecutionService(TestRunMapper testRuns, DryRunMapper dryRuns,
                                   SandboxWorkerClient worker, EventService events, TaskMapper tasks) {
        this.testRuns = testRuns;
        this.dryRuns = dryRuns;
        this.worker = worker;
        this.events = events;
        this.tasks = tasks;
    }

    /**
     * 创建事务提交后的快速触发；真正的互斥由数据库 claim 保证。
     */
    public void executeTestRun(UUID runId) {
        TestRunEntity candidate = testRuns.selectById(runId);
        if (candidate == null) return;
        String token = claimTestRun(candidate);
        if (token == null) return;
        TestRunEntity run = testRuns.selectById(runId);
        publishTest(run);
        try {
            prepareSnapshot(run);
            String expectedHeadCommit = resolveExecutionRef(run);
            WorkerTestExecutionResponse response = worker.executeTests(testRequest(run, expectedHeadCommit));
            requirePassedTestContext(expectedHeadCommit, response);
            Map<String, Object> summary = testSummary(response);
            String status = response != null && "PASSED".equals(response.getStatus()) ? "PASSED" : "FAILED";
            if (completeTest(run, token, status, summary)) cleanupSnapshot(run);
        } catch (RuntimeException failure) {
            if (completeTest(run, token, "FAILED", Map.of("failureCode", failureCode(failure)))) cleanupSnapshot(run);
        }
    }

    /**
     * Task 测试的工作树快照必须在执行器中创建：创建接口只受理任务，不能因 Worker 瞬时不可用
     * 同步返回 FAILED。失败由本次已领取运行持久化真实稳定错误码，恢复调度器可按租约重试。
     */
    private void prepareSnapshot(TestRunEntity run) {
        if (run.getExecutionWorkspaceId() == null) return;
        if (run.getTaskId() == null) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "TEST_RUN_TASK_INVALID", "隔离测试快照缺少关联 Task");
        }
        TaskEntity task = tasks.selectById(run.getTaskId());
        if (task == null || !run.getProjectId().equals(task.getProjectId()) || task.getWorkspaceId() == null) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "TEST_RUN_TASK_INVALID", "隔离测试快照关联的 Task 或 Workspace 不可用");
        }
        worker.createTestSnapshot(task.getWorkspaceId(), run.getProjectRepositoryId(),
                run.getExecutionWorkspaceId(), run.getProjectId(), run.getExecutionSourceRef());
    }

    /**
     * DryRun 先预演合并；无冲突时再在临时 checkout 的合并结果上执行目标分支门禁 Testset。
     */
    public void executeDryRun(UUID runId) {
        DryRunEntity candidate = dryRuns.selectById(runId);
        if (candidate == null) return;
        String token = claimDryRun(candidate);
        if (token == null) return;
        DryRunEntity run = dryRuns.selectById(runId);
        publishDry(run);
        try {
            WorkerMergePreviewRequest previewRequest = new WorkerMergePreviewRequest();
            previewRequest.setRepositoryId(run.getProjectRepositoryId());
            previewRequest.setSourceRef(run.getHeadCommit());
            previewRequest.setTargetBranch(run.getResolvedTargetCommit());
            WorkerMergePreviewResponse preview = worker.mergePreview(previewRequest);
            requirePreviewContext(run, preview);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("targetCommit", preview.getResolvedTargetCommit());
            report.put("mergeable", preview.isMergeable());
            report.put("conflicts", preview.getConflicts() == null ? List.of() : preview.getConflicts());
            String status = "FAILED";
            if (preview.isMergeable()) {
                if (run.getTestsetSnapshot() == null || run.getTestsetSnapshot().isEmpty()) {
                    report.put("tests", Map.of("status", "NOT_REQUIRED", "results", List.of()));
                    status = "PASSED";
                } else {
                    WorkerTestExecutionRequest tests = new WorkerTestExecutionRequest();
                    tests.setExecutionId(run.getId());
                    tests.setProjectId(run.getProjectId());
                    tests.setRepositoryId(run.getProjectRepositoryId());
                    tests.setRef(preview.getResolvedTargetCommit());
                    tests.setMergeSourceRef(preview.getResolvedHeadCommit());
                    tests.setTestsets(items(run.getTestsetSnapshot()));
                    WorkerTestExecutionResponse testResponse = worker.executeTests(tests);
                    requirePassedTestContext(run, testResponse);
                    Map<String, Object> summary = testSummary(testResponse);
                    report.put("tests", summary);
                    status = testResponse != null && "PASSED".equals(testResponse.getStatus()) ? "PASSED" : "FAILED";
                }
            } else {
                report.put("tests", Map.of("status", "SKIPPED", "reason", "MERGE_CONFLICT"));
            }
            completeDry(run, token, status, report, run.getHeadCommit());
        } catch (RuntimeException failure) {
            completeDry(run, token, "FAILED", Map.of("failureCode", failureCode(failure)), null);
        }
    }

    private String claimTestRun(TestRunEntity run) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return testRuns.claim(run.getId(), token, now, now.plus(totalTimeout(run.getExecutionSnapshot()))) == 1
                ? token : null;
    }

    private String claimDryRun(DryRunEntity run) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return dryRuns.claim(run.getId(), token, now, now.plus(totalTimeout(run.getTestsetSnapshot()))) == 1
                ? token : null;
    }

    /**
     * Task 测试在受理时已经由稳定 Workspace 固定到 head/base commit；普通 Test Run 则在
     * 异步执行阶段把用户指定 ref 解析成不可变 SHA，保证一次运行不会在执行途中漂移。
     */
    private String resolveExecutionRef(TestRunEntity run) {
        String ref = run.getExecutionSourceRef();
        if (ref == null || ref.isBlank()) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "TEST_RUN_SOURCE_REF_MISSING", "测试运行缺少源提交或引用");
        }
        if (ref.matches("[0-9a-fA-F]{40,64}")) return ref.toLowerCase(java.util.Locale.ROOT);
        WorkerGitResolveRequest request = new WorkerGitResolveRequest();
        request.setRepositoryId(run.getProjectRepositoryId());
        request.setRef(ref);
        WorkerGitResolveResponse response = worker.resolveGitRef(request);
        String commit = response == null ? null : response.getCommitSha();
        if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}")) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "GIT_RESOLUTION_INVALID", "Sandbox Worker 未返回有效的 commit SHA");
        }
        return commit.toLowerCase(java.util.Locale.ROOT);
    }

    private WorkerTestExecutionRequest testRequest(TestRunEntity run, String executionRef) {
        WorkerTestExecutionRequest request = new WorkerTestExecutionRequest();
        request.setExecutionId(run.getId());
        request.setProjectId(run.getProjectId());
        request.setRepositoryId(run.getProjectRepositoryId());
        if (run.getExecutionWorkspaceId() != null) request.setWorkspaceId(run.getExecutionWorkspaceId());
        else request.setRef(executionRef);
        request.setTestsets(items(run.getExecutionSnapshot()));
        return request;
    }

    private List<WorkerTestExecutionItemRequest> items(List<Map<String, Object>> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return List.of();
        return snapshot.stream().map(value -> {
            WorkerTestExecutionItemRequest item = new WorkerTestExecutionItemRequest();
            item.setTestsetId(UUID.fromString(String.valueOf(value.get("testsetId"))));
            item.setCommand(String.valueOf(value.get("command")));
            item.setTimeoutSeconds(((Number) value.get("timeoutSeconds")).intValue());
            item.setPassRuleType(String.valueOf(value.get("passRuleType")));
            item.setExpectedExitCode(((Number) value.get("expectedExitCode")).intValue());
            return item;
        }).toList();
    }

    private Map<String, Object> testSummary(WorkerTestExecutionResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", response == null ? "FAILED" : response.getStatus());
        summary.put("resolvedHeadCommit", response == null ? null : response.getResolvedHeadCommit());
        summary.put("results", response == null || response.getResults() == null ? List.of()
                : response.getResults().stream().map(result -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("testsetId", result.getTestsetId());
            item.put("status", result.getStatus());
            item.put("exitCode", result.getExitCode());
            item.put("durationMs", result.getDurationMs());
            item.put("failureCode", result.getFailureCode());
            return item;
        }).toList());
        return summary;
    }

    private boolean completeTest(TestRunEntity run, String token, String status, Map<String, Object> summary) {
        if (testRuns.complete(run.getId(), token, status, summary) == 1) {
            publishTest(testRuns.selectById(run.getId()));
            return true;
        }
        return false;
    }

    private void cleanupSnapshot(TestRunEntity run) {
        if (run.getExecutionWorkspaceId() == null) return;
        try {
            worker.deleteWorkspace(run.getExecutionWorkspaceId());
            testRuns.clearExecutionWorkspace(run.getId(), run.getExecutionWorkspaceId());
        } catch (RuntimeException ignored) {
            // 保留 execution_workspace_id，由 janitor 幂等重试。
        }
    }

    private void completeDry(DryRunEntity run, String token, String status, Map<String, Object> report, String head) {
        if (dryRuns.complete(run.getId(), token, status, report, head) == 1)
            publishDry(dryRuns.selectById(run.getId()));
    }

    /**
     * Dry Run 的两个 Git SHA 是创建时冻结的预检上下文。Worker 只能在完全相同的源提交和
     * 目标提交上执行合并预演；否则结果不能作为 MR 前门禁事实。
     */
    private void requirePreviewContext(DryRunEntity run, WorkerMergePreviewResponse preview) {
        if (preview == null || !sameCommit(run.getHeadCommit(), preview.getResolvedHeadCommit())
                || !sameCommit(run.getResolvedTargetCommit(), preview.getResolvedTargetCommit())) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "DRY_RUN_CONTEXT_MISMATCH", "Sandbox Worker returned a merge preview for a different Git context");
        }
    }

    private boolean sameCommit(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    /**
     * 普通 Test Run 和 Task 隔离快照都必须在本次固定提交上执行。Worker 仅在明确返回同一
     * resolvedHeadCommit 时，才允许将 PASSED 持久化为用户可见的测试事实。
     */
    private void requirePassedTestContext(String expectedHeadCommit, WorkerTestExecutionResponse response) {
        if (response != null && "PASSED".equals(response.getStatus())
                && !sameCommit(expectedHeadCommit, response.getResolvedHeadCommit())) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "TEST_RUN_CONTEXT_MISMATCH", "Sandbox Worker passed tests for a different Git commit");
        }
    }

    /**
     * merge --no-commit 后临时工作树的 HEAD 仍是 targetCommit；只有 Worker 明确报告这个
     * 固定基线，PASSED Testset 才能被用作 MR 前门禁。
     */
    private void requirePassedTestContext(DryRunEntity run, WorkerTestExecutionResponse response) {
        if (response != null && "PASSED".equals(response.getStatus())
                && !sameCommit(run.getResolvedTargetCommit(), response.getResolvedHeadCommit())) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "DRY_RUN_TEST_CONTEXT_MISMATCH", "Sandbox Worker passed tests for a different target commit");
        }
    }

    private void publishTest(TestRunEntity run) {
        if (run == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", run.getProjectId());
        payload.put("testRunId", run.getId());
        payload.put("repositoryId", run.getProjectRepositoryId());
        payload.put("status", run.getStatus());
        if (run.getTaskId() != null) payload.put("taskId", run.getTaskId());
        payload.put("timestamp", Instant.now().toString());
        events.publish(run.getProjectId(), null, "test-run.updated", run.getId().toString(), payload);
    }

    private void publishDry(DryRunEntity run) {
        if (run == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", run.getProjectId());
        payload.put("dryRunId", run.getId());
        payload.put("repositoryId", run.getProjectRepositoryId());
        payload.put("status", run.getStatus());
        if (run.getTaskId() != null) payload.put("taskId", run.getTaskId());
        payload.put("headCommit", run.getHeadCommit());
        payload.put("targetBranch", run.getTargetBranch());
        payload.put("timestamp", Instant.now().toString());
        events.publish(run.getProjectId(), null, "dry-run.updated", run.getId().toString(), payload);
    }

    private String failureCode(RuntimeException failure) {
        return failure instanceof qg.qgent.api.ApiException api ? api.code() : "EXECUTION_FAILED";
    }

    private Duration totalTimeout(List<Map<String, Object>> snapshot) {
        long seconds = snapshot == null ? 0 : snapshot.stream()
                .map(value -> value.get("timeoutSeconds"))
                .filter(Number.class::isInstance).map(Number.class::cast)
                .mapToLong(Number::longValue).sum();
        return Duration.ofSeconds(Math.max(1, seconds)).plus(LEASE_MARGIN);
    }
}
