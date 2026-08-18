package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveRequest;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 受控 Test Run 与 Dry Run 服务。
 * 仅管理配置与状态，真实执行由执行服务承担（202 接缝）；testsetIds 必须属于该仓库且为 ENABLED，
 * MR 前 Dry Run 的目标分支必选测试集由分支门禁决定。普通 TestRun 仅执行用户显式选择的
 * Testset，不能因仓库默认分支的门禁而改变其测试目标。
 * 创建时先持久化 QUEUED，事务提交后由执行服务推进状态并写入真实结果；
 * 受保护分支必选测试集以请求 targetBranch 的 branch config 为准；执行时由 WorkspaceRepository 的源分支与头提交精确校验。
 */
@Service
public class TestRunService {
    private static final Set<String> TESTABLE_TASK_STATUSES = Set.of(
            "WAITING_DIFF_CONFIRMATION", "WAITING_PREFLIGHT", "SUCCEEDED", "DELIVERY_FAILED",
            "FAILED", "CANCELLED", "DIFF_REJECTED");
    private final TestRunMapper testRunMapper;
    private final DryRunMapper dryRunMapper;
    private final ProjectRepositoryMapper repositoryMapper;
    private final TestsetMapper testsetMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;
    private final TaskMapper taskMapper;
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper;
    private final TestRunExecutionDispatcher executionDispatcher;
    private final SandboxWorkerClient worker;
    private final GitStoreSyncService gitStores;

    public TestRunService(TestRunMapper testRunMapper, DryRunMapper dryRunMapper,
                          ProjectRepositoryMapper repositoryMapper, TestsetMapper testsetMapper,
                          RepositoryBranchConfigMapper branchConfigMapper,
                          RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
                          EventService eventService, TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                          TestRunExecutionDispatcher executionDispatcher, SandboxWorkerClient worker,
                          GitStoreSyncService gitStores) {
        this.testRunMapper = testRunMapper;
        this.dryRunMapper = dryRunMapper;
        this.repositoryMapper = repositoryMapper;
        this.testsetMapper = testsetMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
        this.taskMapper = taskMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.executionDispatcher = executionDispatcher;
        this.worker = worker;
        this.gitStores = gitStores;
    }

    /**
     * 发起受控测试运行。
     * 校验 repositoryId 归属项目、taskId 与 ref 二选一、testsetIds 属于仓库且 ENABLED，
     * 普通 TestRun 只执行用户选定的 Testset；受理后持久化 QUEUED 并发布 test-run.updated。
     *
     * @return 新测试运行摘要（受理态，真实执行后续由执行服务推进）
     */
    public TestRunResponse createTestRun(UUID projectId, UUID userId, TestRunCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        ProjectRepositoryEntity repo = requireRepository(projectId, request.getRepositoryId());
        boolean hasTask = request.getTaskId() != null;
        boolean hasRef = request.getRef() != null && !request.getRef().isBlank();
        if (hasTask == hasRef) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_RUN_TARGET",
                    "taskId 与 ref 必须二选一");
        }
        List<TestsetEntity> selectedTestsets = validateTestsets(projectId, request);
        WorkspaceRepositoryEntity taskWorktree = hasTask
                ? requireTaskWorktree(projectId, request.getTaskId(), request.getRepositoryId()) : null;
        // 所有 Test Run 先受理再异步执行。非 Task 测试传入的分支名只能在执行器中解析，
        // 否则 Worker 短暂不可用会让本应返回 QUEUED 的请求同步失败。
        String executionRef = hasTask ? executionRef(taskWorktree) : request.getRef().trim();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TestRunEntity run = new TestRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setProjectRepositoryId(request.getRepositoryId());
        run.setTaskId(request.getTaskId());
        run.setRef(request.getRef());
        run.setTestsetIds(request.getTestsetIds().stream().map(UUID::toString).toList());
        run.setExecutionSnapshot(selectedTestsets.stream().map(this::snapshot).toList());
        run.setExecutionSourceRef(executionRef);
        if (hasTask) {
            UUID snapshotWorkspaceId = UUID.nameUUIDFromBytes(
                    ("qgents-test-snapshot:" + run.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            run.setExecutionWorkspaceId(snapshotWorkspaceId);
        }
        run.setStatus("QUEUED");
        run.setAttemptCount(0);
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        testRunMapper.insert(run);
        publishTestRunUpdated(run);
        afterCommit(() -> executionDispatcher.dispatchTestRun(run.getId()));
        return toTestRun(run);
    }

    /**
     * 获取测试运行状态、用例摘要和产物引用。
     */
    public TestRunResponse testRun(UUID projectId, UUID testRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TestRunEntity run = testRunMapper.selectById(testRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEST_RUN_NOT_FOUND", "测试运行不存在或不可见");
        }
        return toTestRun(run);
    }

    /**
     * 针对源分支和目标分支发起合并前试运行。
     * 校验 repositoryId 归属项目；受理后持久化 QUEUED 并发布 dry-run.updated。
     */
    public DryRunResponse createDryRun(UUID projectId, UUID userId, DryRunCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        ProjectRepositoryEntity repository = requireRepository(projectId, request.getRepositoryId());
        String sourceRef = request.getSourceRef().trim();
        // 门禁查询、Worker 同步和预检匹配必须使用同一个规范化后的分支名。
        String targetBranch = gitStores.normalizeTargetBranch(request.getTargetBranch());
        WorkspaceRepositoryEntity taskWorktree = null;
        if (request.getTaskId() != null) {
            taskWorktree = requireTaskWorktree(projectId, request.getTaskId(), request.getRepositoryId());
            if (!sourceRef.equals(taskWorktree.getSourceBranch())
                    && (taskWorktree.getHeadCommit() == null || !sourceRef.equals(taskWorktree.getHeadCommit()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DRY_RUN_TASK_SOURCE_REF_MISMATCH",
                        "带 taskId 的 sourceRef 必须是该 Task Workspace 的 sourceBranch 或 headCommit");
            }
        }
        List<TestsetEntity> requiredTestsets = requiredTestsets(repository, targetBranch);
        // 先同步 GitHub 当前目标分支再固定 SHA，避免 Dry Run 在 Worker 的旧 Git Store 上误通过。
        String resolvedTarget = gitStores.refreshTargetBranch(projectId, repository, targetBranch);
        String resolvedHead = resolveCommit(request.getRepositoryId(), sourceRef);
        // Task 预检只能验证该 Task 已推送的当前 HEAD。否则 sourceBranch 可能解析到远端旧提交，
        // 产生“Dry Run 通过但当前待创建 MR 的代码没有被测试”的假阳性。
        if (taskWorktree != null && (taskWorktree.getHeadCommit() == null
                || !resolvedHead.equalsIgnoreCase(taskWorktree.getHeadCommit()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_TASK_HEAD_NOT_PUSHED",
                    "Task 当前提交尚未推送或与 Dry Run 源提交不一致");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DryRunEntity run = new DryRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setProjectRepositoryId(request.getRepositoryId());
        run.setTaskId(request.getTaskId());
        run.setSourceRef(sourceRef);
        run.setHeadCommit(resolvedHead);
        run.setResolvedTargetCommit(resolvedTarget);
        run.setTargetBranch(targetBranch);
        run.setStatus("QUEUED");
        run.setTestsetSnapshot(requiredTestsets.stream().map(this::snapshot).toList());
        run.setAttemptCount(0);
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        dryRunMapper.insert(run);
        publishDryRunUpdated(run);
        afterCommit(() -> executionDispatcher.dispatchDryRun(run.getId()));
        return toDryRun(run);
    }

    /**
     * 获取试运行报告和冲突、测试摘要。
     */
    public DryRunReportResponse dryRunReport(UUID projectId, UUID dryRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        DryRunEntity run = dryRunMapper.selectById(dryRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DRY_RUN_NOT_FOUND", "试运行不存在或不可见");
        }
        return new DryRunReportResponse(id(run.getId()), run.getStatus(), run.getReport(), iso(run.getCreatedAt()));
    }

    // ---------- 私有辅助 ----------

    private ProjectRepositoryEntity requireRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        return repo;
    }

    private WorkspaceRepositoryEntity requireTaskWorktree(UUID projectId, UUID taskId, UUID repositoryId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId()) || task.getWorkspaceId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_RUN_TASK_INVALID",
                    "Task 不属于当前项目或尚未准备 Workspace");
        }
        if (!TESTABLE_TASK_STATUSES.contains(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_RUN_TASK_WORKSPACE_UNSTABLE",
                    "Task 正在规划、执行或交付，工作树尚未稳定，不能发起测试");
        }
        WorkspaceRepositoryEntity worktree = workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(repository -> repositoryId.equals(repository.getProjectRepositoryId())).findFirst().orElse(null);
        if (worktree == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEST_RUN_REPOSITORY_NOT_IN_WORKSPACE",
                    "Task Workspace 不包含目标仓库");
        }
        return worktree;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 校验请求的 testsetIds 均属于该仓库且为 ENABLED。
     */
    private List<TestsetEntity> validateTestsets(UUID projectId, TestRunCreateRequest request) {
        if (request.getTestsetIds().size() > 32
                || request.getTestsetIds().stream().distinct().count() != request.getTestsetIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET_IDS", "testsetIds 最多 32 个且不能重复");
        }
        java.util.ArrayList<TestsetEntity> result = new java.util.ArrayList<>();
        for (UUID testsetId : request.getTestsetIds()) {
            TestsetEntity testset = testsetMapper.selectById(testsetId);
            if (testset == null || !testset.getProjectId().equals(projectId)
                    || testset.getProjectRepositoryId() == null
                    || !testset.getProjectRepositoryId().equals(request.getRepositoryId())
                    || !"ENABLED".equals(testset.getStatus())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TESTSET_NOT_ELIGIBLE",
                        "testsetId " + testsetId + " 必须属于该仓库且为 ENABLED");
            }
            snapshot(testset);
            result.add(testset);
        }
        return List.copyOf(result);
    }

    private List<TestsetEntity> requiredTestsets(ProjectRepositoryEntity repository, String targetBranch) {
        RepositoryBranchConfigEntity config = branchConfigMapper.selectOne(
                Wrappers.<RepositoryBranchConfigEntity>lambdaQuery()
                        .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, repository.getId())
                        .eq(RepositoryBranchConfigEntity::getBranchName, targetBranch));
        if (config == null) return List.of();
        return branchConfigTestsetMapper.selectByBranchConfigId(config.getId()).stream().map(relation -> {
            TestsetEntity testset = testsetMapper.selectById(relation.getTestsetId());
            if (testset == null || !repository.getProjectId().equals(testset.getProjectId())
                    || !repository.getId().equals(testset.getProjectRepositoryId())
                    || !"ENABLED".equals(testset.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_GATE_TESTSET_INVALID",
                        "目标分支门禁引用了不可执行的 Testset");
            }
            snapshot(testset);
            return testset;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(TestsetEntity testset) {
        Map<String, Object> definition = testset.getDefinition();
        if (definition == null || !(definition.get("command") instanceof String command) || command.isBlank()
                || !(definition.get("timeoutSeconds") instanceof Number timeout)
                || timeout.intValue() < 1 || timeout.intValue() > 3600
                || !(definition.get("passRule") instanceof Map<?, ?> rawRule)) {
            throw new ApiException(HttpStatus.CONFLICT, "TESTSET_DEFINITION_INVALID",
                    "Testset 定义缺失或仍使用旧格式，不能创建可恢复运行");
        }
        Map<String, Object> rule = (Map<String, Object>) rawRule;
        if (!"EXIT_CODE".equals(String.valueOf(rule.get("type"))) || !(rule.get("expected") instanceof Number)) {
            throw new ApiException(HttpStatus.CONFLICT, "TESTSET_DEFINITION_INVALID", "Testset 通过规则无效");
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        copy.put("testsetId", testset.getId().toString());
        copy.put("command", command.trim());
        copy.put("timeoutSeconds", timeout.intValue());
        copy.put("passRuleType", "EXIT_CODE");
        copy.put("expectedExitCode", ((Number) rule.get("expected")).intValue());
        return java.util.Collections.unmodifiableMap(copy);
    }

    private String executionRef(WorkspaceRepositoryEntity worktree) {
        if (worktree.getHeadCommit() != null && !worktree.getHeadCommit().isBlank()) return worktree.getHeadCommit();
        if (worktree.getBaseCommit() != null && !worktree.getBaseCommit().isBlank()) return worktree.getBaseCommit();
        throw new ApiException(HttpStatus.CONFLICT, "TEST_RUN_TASK_HEAD_MISSING", "Task Workspace 缺少可隔离测试的提交");
    }

    private String resolveCommit(UUID repositoryId, String reference) {
        WorkerGitResolveRequest request = new WorkerGitResolveRequest();
        request.setRepositoryId(repositoryId);
        request.setRef(reference);
        WorkerGitResolveResponse response = worker.resolveGitRef(request);
        String commit = response == null ? null : response.getCommitSha();
        if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_RESOLUTION_INVALID",
                    "Sandbox Worker 未返回有效的 commit SHA");
        }
        return commit.toLowerCase(java.util.Locale.ROOT);
    }

    private void cleanupSnapshot(TestRunEntity run) {
        if (run.getExecutionWorkspaceId() == null) return;
        try {
            worker.deleteWorkspace(run.getExecutionWorkspaceId());
            if (testRunMapper.clearExecutionWorkspace(run.getId(), run.getExecutionWorkspaceId()) == 1) {
                run.setExecutionWorkspaceId(null);
            }
        } catch (RuntimeException ignored) {
            // 保留 execution_workspace_id，定时 janitor 会再次执行幂等删除。
        }
    }

    private void publishTestRunUpdated(TestRunEntity run) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", run.getProjectId());
        p.put("testRunId", run.getId());
        p.put("repositoryId", run.getProjectRepositoryId());
        if (run.getTaskId() != null) {
            p.put("taskId", run.getTaskId());
        }
        p.put("ref", run.getRef());
        p.put("status", run.getStatus());
        p.put("sequence", 0);
        p.put("timestamp", Instant.now().toString());
        eventService.publish(run.getProjectId(), null, "test-run.updated", run.getId().toString(), p);
    }

    private void publishDryRunUpdated(DryRunEntity run) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", run.getProjectId());
        p.put("dryRunId", run.getId());
        p.put("repositoryId", run.getProjectRepositoryId());
        if (run.getTaskId() != null) {
            p.put("taskId", run.getTaskId());
        }
        p.put("headCommit", run.getHeadCommit());
        p.put("targetBranch", run.getTargetBranch());
        p.put("status", run.getStatus());
        p.put("sequence", 0);
        p.put("timestamp", Instant.now().toString());
        eventService.publish(run.getProjectId(), null, "dry-run.updated", run.getId().toString(), p);
    }

    private TestRunResponse toTestRun(TestRunEntity run) {
        return new TestRunResponse(id(run.getId()), id(run.getProjectId()), id(run.getProjectRepositoryId()),
                run.getRef(), run.getTestsetIds(), run.getStatus(), run.getSummary(),
                id(run.getCreatedBy()), iso(run.getCreatedAt()));
    }

    private DryRunResponse toDryRun(DryRunEntity run) {
        return new DryRunResponse(id(run.getId()), id(run.getProjectId()), id(run.getProjectRepositoryId()),
                run.getHeadCommit(), run.getTargetBranch(), run.getStatus(),
                run.getReport(), id(run.getCreatedBy()), iso(run.getCreatedAt()));
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
