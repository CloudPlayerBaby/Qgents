package qg.qgent.service;

import org.springframework.stereotype.Service;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerMergePreviewRequest;
import qg.qgent.orchestration.worker.WorkerMergePreviewResponse;
import qg.qgent.orchestration.worker.WorkerTestExecutionItemRequest;
import qg.qgent.orchestration.worker.WorkerTestExecutionRequest;
import qg.qgent.orchestration.worker.WorkerTestExecutionResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 通过数据库租约领取 TestRun/DryRun，支持多实例并发与进程重启恢复。 */
@Service
public class TestRunExecutionService {
    private static final Duration LEASE_MARGIN = Duration.ofMinutes(10);
    private final TestRunMapper testRuns;
    private final DryRunMapper dryRuns;
    private final SandboxWorkerClient worker;
    private final EventService events;

    public TestRunExecutionService(TestRunMapper testRuns, DryRunMapper dryRuns,
            SandboxWorkerClient worker, EventService events) {
        this.testRuns = testRuns;
        this.dryRuns = dryRuns;
        this.worker = worker;
        this.events = events;
    }

    /** 创建事务提交后的快速触发；真正的互斥由数据库 claim 保证。 */
    public void executeTestRun(UUID runId) {
        TestRunEntity candidate = testRuns.selectById(runId);
        if (candidate == null) return;
        String token = claimTestRun(candidate);
        if (token == null) return;
        TestRunEntity run = testRuns.selectById(runId);
        publishTest(run);
        try {
            WorkerTestExecutionResponse response = worker.executeTests(testRequest(run));
            Map<String, Object> summary = testSummary(response);
            String status = response != null && "PASSED".equals(response.getStatus()) ? "PASSED" : "FAILED";
            if (completeTest(run, token, status, summary)) cleanupSnapshot(run);
        } catch (RuntimeException failure) {
            if (completeTest(run, token, "FAILED", Map.of("failureCode", failureCode(failure)))) cleanupSnapshot(run);
        }
    }

    /** DryRun 先预演合并；无冲突时再在临时 checkout 的合并结果上执行目标分支门禁 Testset。 */
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

    private WorkerTestExecutionRequest testRequest(TestRunEntity run) {
        WorkerTestExecutionRequest request = new WorkerTestExecutionRequest();
        request.setExecutionId(run.getId());
        request.setProjectId(run.getProjectId());
        request.setRepositoryId(run.getProjectRepositoryId());
        if (run.getExecutionWorkspaceId() != null) request.setWorkspaceId(run.getExecutionWorkspaceId());
        else request.setRef(run.getExecutionSourceRef());
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
        if (dryRuns.complete(run.getId(), token, status, report, head) == 1) publishDry(dryRuns.selectById(run.getId()));
    }

    private void publishTest(TestRunEntity run) {
        if (run == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", run.getProjectId()); payload.put("testRunId", run.getId());
        payload.put("repositoryId", run.getProjectRepositoryId()); payload.put("status", run.getStatus());
        if (run.getTaskId() != null) payload.put("taskId", run.getTaskId());
        payload.put("timestamp", Instant.now().toString());
        events.publish(run.getProjectId(), null, "test-run.updated", run.getId().toString(), payload);
    }

    private void publishDry(DryRunEntity run) {
        if (run == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", run.getProjectId()); payload.put("dryRunId", run.getId());
        payload.put("repositoryId", run.getProjectRepositoryId()); payload.put("status", run.getStatus());
        if (run.getTaskId() != null) payload.put("taskId", run.getTaskId());
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
