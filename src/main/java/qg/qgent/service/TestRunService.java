package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
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
    private static final int LIST_DEFAULT_LIMIT = 20;
    private static final int LIST_MAX_LIMIT = 100;
    private static final Set<String> TEST_RUN_LIST_STATUSES = Set.of(
            "QUEUED", "RUNNING", "PASSED", "FAILED", "CANCELLED");
    private static final Set<String> DRY_RUN_LIST_STATUSES = Set.of(
            "QUEUED", "RUNNING", "PASSED", "FAILED", "CONFLICT", "CANCELLED");
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
     * 查询项目 Test Run 历史。列表只返回轻量生命周期摘要，详情仍通过单条接口读取。
     * 游标绑定 createdAt 与 id，避免同一时间创建的运行在翻页时重复或丢失。
     */
    public ApiPageResponse<TestRunListItemResponse> listTestRuns(UUID projectId, UUID userId,
                                                                  UUID repositoryId, UUID taskId,
                                                                  String status, UUID createdByUserId,
                                                                  String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        int size = clampListLimit(limit);
        Set<String> statuses = parseStatuses(status, TEST_RUN_LIST_STATUSES);
        ListCursor position = decodeListCursor(cursor);
        List<TestRunEntity> rows = testRunMapper.selectList(Wrappers.<TestRunEntity>lambdaQuery()
                .eq(TestRunEntity::getProjectId, projectId)
                .eq(repositoryId != null, TestRunEntity::getProjectRepositoryId, repositoryId)
                .eq(taskId != null, TestRunEntity::getTaskId, taskId)
                .eq(createdByUserId != null, TestRunEntity::getCreatedBy, createdByUserId)
                .in(!statuses.isEmpty(), TestRunEntity::getStatus, statuses)
                .and(position != null, q -> q.lt(TestRunEntity::getCreatedAt, position.createdAt())
                        .or(x -> x.eq(TestRunEntity::getCreatedAt, position.createdAt())
                                .lt(TestRunEntity::getId, position.id())))
                .orderByDesc(TestRunEntity::getCreatedAt)
                .orderByDesc(TestRunEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<TestRunEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<TestRunListItemResponse> items = page.stream().map(this::toTestRunListItem).toList();
        String next = hasMore && !page.isEmpty() ? encodeListCursor(page.getLast().getCreatedAt(), page.getLast().getId()) : null;
        return new ApiPageResponse<>(items, new PageMeta(next, hasMore), requestId);
    }

    /**
     * 查询项目 Dry Run 历史。列表不携带 report 或测试结果，详情通过报告接口读取。
     */
    public ApiPageResponse<DryRunListItemResponse> listDryRuns(UUID projectId, UUID userId,
                                                                UUID repositoryId, UUID taskId,
                                                                String status, String targetBranch,
                                                                UUID createdByUserId, String cursor,
                                                                int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        int size = clampListLimit(limit);
        Set<String> statuses = parseStatuses(status, DRY_RUN_LIST_STATUSES);
        ListCursor position = decodeListCursor(cursor);
        List<DryRunEntity> rows = dryRunMapper.selectList(Wrappers.<DryRunEntity>lambdaQuery()
                .eq(DryRunEntity::getProjectId, projectId)
                .eq(repositoryId != null, DryRunEntity::getProjectRepositoryId, repositoryId)
                .eq(taskId != null, DryRunEntity::getTaskId, taskId)
                .eq(targetBranch != null && !targetBranch.isBlank(), DryRunEntity::getTargetBranch,
                        targetBranch == null ? null : targetBranch.trim())
                .eq(createdByUserId != null, DryRunEntity::getCreatedBy, createdByUserId)
                .in(!statuses.isEmpty(), DryRunEntity::getStatus, statuses)
                .and(position != null, q -> q.lt(DryRunEntity::getCreatedAt, position.createdAt())
                        .or(x -> x.eq(DryRunEntity::getCreatedAt, position.createdAt())
                                .lt(DryRunEntity::getId, position.id())))
                .orderByDesc(DryRunEntity::getCreatedAt)
                .orderByDesc(DryRunEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<DryRunEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<DryRunListItemResponse> items = page.stream().map(this::toDryRunListItem).toList();
        String next = hasMore && !page.isEmpty() ? encodeListCursor(page.getLast().getCreatedAt(), page.getLast().getId()) : null;
        return new ApiPageResponse<>(items, new PageMeta(next, hasMore), requestId);
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
        return createDryRunWithoutAccessCheck(projectId, userId, request);
    }

    private DryRunResponse createDryRunWithoutAccessCheck(UUID projectId, UUID userId,
                                                           DryRunCreateRequest request) {
        return createDryRunWithoutAccessCheck(projectId, userId, request, null, null);
    }

    private DryRunResponse createDryRunWithoutAccessCheck(UUID projectId, UUID userId,
                                                           DryRunCreateRequest request,
                                                           UUID retryOfDryRunId, String retryReasonCode) {
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
        run.setRetryOfDryRunId(retryOfDryRunId);
        run.setRetryReasonCode(retryReasonCode);
        run.setActiveClaimKey(activeClaimKey(projectId, request.getTaskId(), request.getRepositoryId(),
                resolvedHead, targetBranch, resolvedTarget));
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        dryRunMapper.insert(run);
        publishDryRunUpdated(run);
        afterCommit(() -> executionDispatcher.dispatchDryRun(run.getId()));
        return toDryRun(run);
    }

    /**
     * 为 MR_FIRST 自动发起一条仓库级 Dry Run。
     * <p>
     * 该入口只由内部自动化调用，不暴露给 Controller。它复用公开 Dry Run 的完整校验和
     * Testset 快照逻辑，并在 Worker/GitHub 调用前检查同一 Task、仓库、HEAD 和目标分支的
     * 活跃运行，避免 delivery.started 重复投递导致重复测试。
     */
    public DryRunResponse createAutomaticDryRun(UUID projectId, UUID taskId, UUID repositoryId,
                                                String targetBranch) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId()) || task.getWorkspaceId() == null
                || !"MR_FIRST".equals(task.getDeliveryMode())
                || !"WAITING_PREFLIGHT".equals(task.getStatus())) {
            return null;
        }
        WorkspaceRepositoryEntity worktree = workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> repositoryId.equals(value.getProjectRepositoryId())).findFirst().orElse(null);
        if (worktree == null || worktree.getHeadCommit() == null || worktree.getHeadCommit().isBlank()) {
            return null;
        }
        String branch = gitStores.normalizeTargetBranch(targetBranch);
        ProjectRepositoryEntity repository = requireRepository(projectId, repositoryId);
        // 先刷新目标分支，再按固定 targetCommit 判断是否已有可复用的运行；否则目标分支推进后
        // 旧 QUEUED Dry Run 会被错误复用，后续门禁只能在最后一步才发现上下文过期。
        String currentTarget = gitStores.refreshTargetBranch(projectId, repository, branch);
        DryRunEntity latest = latestDryRun(projectId, taskId, repositoryId, worktree.getHeadCommit(), branch);
        // 当前 head 被确定性合并冲突阻塞时，探测远端 source 分支是否已推进（用户在 GitHub
        // 手工解决冲突不会经过 Worker push）。推进则回填 worktree head，为新的 head 建立 dry-run。
        if (latest != null && "FAILED".equals(latest.getStatus()) && isDeterministicConflict(latest)
                && worktree.getSourceBranch() != null && !worktree.getSourceBranch().isBlank()) {
            String newHead = gitStores.refreshSourceHead(projectId, worktree, repository, task.getWorkspaceId());
            if (newHead != null) {
                worktree.setHeadCommit(newHead);
                latest = latestDryRun(projectId, taskId, repositoryId, worktree.getHeadCommit(), branch);
            }
        }
        if (latest != null && currentTarget.equalsIgnoreCase(latest.getResolvedTargetCommit())
                && List.of("QUEUED", "RUNNING", "PASSED").contains(latest.getStatus())) {
            return toDryRun(latest);
        }
        if (latest != null && "FAILED".equals(latest.getStatus())) {
            if (isDeterministicConflict(latest)) {
                // 确定性冲突：head/target 未变前无条件复用，避免同键 FAILED 被调度器反复新建堆积。
                return toDryRun(latest);
            }
            if (latest.getUpdatedAt() != null
                    && latest.getUpdatedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30))) {
                // 短暂 Worker/GitHub 故障交给下一轮恢复，避免同一错误被调度器高频放大。
                return toDryRun(latest);
            }
        }
        UUID retrySource = null;
        String retryReason = null;
        if (latest != null && "FAILED".equals(latest.getStatus())) {
            String failureCode = reportFailureCode(latest);
            if (failureCode == null || !AUTOMATIC_RETRYABLE_DRY_RUN_CODES.contains(failureCode)) {
                return toDryRun(latest);
            }
            retrySource = latest.getId();
            retryReason = failureCode;
        }
        DryRunCreateRequest request = new DryRunCreateRequest();
        request.setRepositoryId(repositoryId);
        request.setTaskId(taskId);
        request.setSourceRef(worktree.getHeadCommit());
        request.setTargetBranch(branch);
        // Task 发起人只是持久化 createdBy 的审计主体；该内部入口不依赖其当前登录会话。
        try {
            return createDryRunWithoutAccessCheck(projectId, task.getCreatedBy(), request, retrySource, retryReason);
        } catch (DuplicateKeyException duplicate) {
            DryRunEntity claimed = dryRunMapper.selectOne(Wrappers.<DryRunEntity>lambdaQuery()
                    .eq(DryRunEntity::getProjectId, projectId).eq(DryRunEntity::getTaskId, taskId)
                    .eq(DryRunEntity::getProjectRepositoryId, repositoryId)
                    .eq(DryRunEntity::getHeadCommit, worktree.getHeadCommit())
                    .eq(DryRunEntity::getTargetBranch, branch)
                    .orderByDesc(DryRunEntity::getCreatedAt).last("LIMIT 1"));
            return claimed == null ? null : toDryRun(claimed);
        }
    }

    /**
     * 按 Task/仓库/head/targetBranch 查询最新的一条 Dry Run，供自动预检复用判断。
     */
    private DryRunEntity latestDryRun(UUID projectId, UUID taskId, UUID repositoryId, String headCommit,
                                      String targetBranch) {
        return dryRunMapper.selectOne(Wrappers.<DryRunEntity>lambdaQuery()
                .eq(DryRunEntity::getProjectId, projectId)
                .eq(DryRunEntity::getTaskId, taskId)
                .eq(DryRunEntity::getProjectRepositoryId, repositoryId)
                .eq(DryRunEntity::getHeadCommit, headCommit)
                .eq(DryRunEntity::getTargetBranch, targetBranch)
                .orderByDesc(DryRunEntity::getCreatedAt).last("LIMIT 1"));
    }

    /**
     * 判断一条 FAILED Dry Run 是否为确定性合并冲突（head/target 未变则不会自愈）。
     * <p>
     * 区分两种冲突表示：冲突预演路径在 report.tests.reason 标记 MERGE_CONFLICT；
     * 合并测试路径在 report.failureCode 返回 GIT_MERGE_CONFLICT。这类失败不能通过重试解决，
     * 只能等 head 或 target 变化后重新预检，因此自动调度应无条件复用而非反复新建。
     */
    static boolean isDeterministicConflict(DryRunEntity run) {
        Map<String, Object> report = run.getReport();
        if (report == null) {
            return false;
        }
        if ("GIT_MERGE_CONFLICT".equals(report.get("failureCode"))) {
            return true;
        }
        Object tests = report.get("tests");
        return tests instanceof Map<?, ?> map && "MERGE_CONFLICT".equals(map.get("reason"));
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
        return new DryRunReportResponse(id(run.getId()), run.getStatus(), run.getReport(), run.getHeadCommit(),
                run.getTargetBranch(), run.getResolvedTargetCommit(), run.getAttemptCount(), iso(run.getCreatedAt()),
                iso(run.getUpdatedAt()));
    }

    /**
     * 仅复制已经失败且可重试的基础设施 Dry Run。整个过程不调用 Worker 或 GitHub；数据库锁只用于
     * 阻止同一来源并发创建多个续跑事实，真正执行仍在提交后异步触发。
     */
    @Transactional
    public DryRunResponse retryDryRun(UUID projectId, UUID dryRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        DryRunEntity source = dryRunMapper.selectByIdForUpdate(dryRunId);
        if (source == null || !projectId.equals(source.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DRY_RUN_NOT_FOUND", "试运行不存在或不可见");
        }
        requireRepository(projectId, source.getProjectRepositoryId());
        if (!"FAILED".equals(source.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_RETRY_NOT_ALLOWED", "只有失败的 Dry Run 可以重试");
        }
        String code = reportFailureCode(source);
        if (code == null || !RETRYABLE_DRY_RUN_CODES.contains(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_RETRY_NOT_ALLOWED",
                    "该 Dry Run 失败类型不能通过基础设施重试解决");
        }
        if (retryDepth(source) >= MAX_DRY_RUN_RETRIES) {
            throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_RETRY_EXHAUSTED", "Dry Run 已达到最大重试次数");
        }
        if (dryRunMapper.selectCount(Wrappers.<DryRunEntity>query()
                .eq("retry_of_dry_run_id", source.getId())) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DRY_RUN_RETRY_IN_PROGRESS", "该 Dry Run 已有进行中的重试");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DryRunEntity retry = new DryRunEntity();
        retry.setId(UuidV7.next());
        retry.setProjectId(source.getProjectId());
        retry.setTaskId(source.getTaskId());
        retry.setTaskStepId(source.getTaskStepId());
        retry.setProjectRepositoryId(source.getProjectRepositoryId());
        retry.setSourceRef(source.getSourceRef());
        retry.setHeadCommit(source.getHeadCommit());
        retry.setResolvedTargetCommit(source.getResolvedTargetCommit());
        retry.setTargetBranch(source.getTargetBranch());
        retry.setStatus("QUEUED");
        retry.setTestsetSnapshot(source.getTestsetSnapshot() == null ? List.of() : List.copyOf(source.getTestsetSnapshot()));
        retry.setAttemptCount(0);
        retry.setRetryOfDryRunId(source.getId());
        retry.setRetryReasonCode(code);
        retry.setActiveClaimKey(activeClaimKey(source.getProjectId(), source.getTaskId(), source.getProjectRepositoryId(),
                source.getHeadCommit(), source.getTargetBranch(), source.getResolvedTargetCommit()));
        retry.setCreatedBy(userId);
        retry.setCreatedAt(now);
        retry.setUpdatedAt(now);
        dryRunMapper.insert(retry);
        afterCommit(() -> {
            publishDryRunUpdated(retry);
            executionDispatcher.dispatchDryRun(retry.getId());
        });
        return toDryRun(retry);
    }

    // ---------- 私有辅助 ----------

    private ProjectRepositoryEntity requireRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repo = repositoryMapper.selectById(repositoryId);
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        if (!"ACTIVE".equals(repo.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_UNBOUND",
                    "项目仓库绑定已解绑，不能创建新的测试或预演运行");
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
        p.put("targetCommit", run.getResolvedTargetCommit());
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
                run.getHeadCommit(), run.getTargetBranch(), run.getResolvedTargetCommit(), run.getStatus(),
                run.getReport(), id(run.getCreatedBy()), iso(run.getCreatedAt()));
    }

    private TestRunListItemResponse toTestRunListItem(TestRunEntity run) {
        return new TestRunListItemResponse(id(run.getId()), id(run.getProjectId()),
                id(run.getProjectRepositoryId()), run.getTestsetIds() == null ? List.of() : run.getTestsetIds(),
                id(run.getTaskId()), run.getRef() == null ? run.getExecutionSourceRef() : run.getRef(),
                run.getStatus(), id(run.getCreatedBy()), iso(run.getCreatedAt()), iso(run.getStartedAt()),
                iso(run.getFinishedAt()));
    }

    private DryRunListItemResponse toDryRunListItem(DryRunEntity run) {
        return new DryRunListItemResponse(id(run.getId()), id(run.getProjectId()),
                id(run.getProjectRepositoryId()), run.getSourceRef(), run.getTargetBranch(), id(run.getTaskId()),
                run.getStatus(), id(run.getCreatedBy()), iso(run.getCreatedAt()), iso(run.getStartedAt()),
                iso(run.getFinishedAt()));
    }

    private int clampListLimit(int limit) {
        return limit <= 0 ? LIST_DEFAULT_LIMIT : Math.min(limit, LIST_MAX_LIMIT);
    }

    private Set<String> parseStatuses(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String value = item.trim().toUpperCase(Locale.ROOT);
            if (value.isBlank() || !allowed.contains(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_FILTER",
                        "status 包含不支持的状态值");
            }
            values.add(value);
        }
        return Set.copyOf(values);
    }

    private String encodeListCursor(LocalDateTime createdAt, UUID id) {
        if (createdAt == null || id == null) {
            return null;
        }
        String raw = "createdAt:" + createdAt + "|id:" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ListCursor decodeListCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 2 || !fields[0].startsWith("createdAt:") || !fields[1].startsWith("id:")) {
                throw new IllegalArgumentException();
            }
            LocalDateTime createdAt = LocalDateTime.parse(fields[0].substring("createdAt:".length()));
            UUID id = UUID.fromString(fields[1].substring("id:".length()));
            return new ListCursor(createdAt, id);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private record ListCursor(LocalDateTime createdAt, UUID id) {
    }

    private static final int MAX_DRY_RUN_RETRIES = 3;
    private static final Set<String> RETRYABLE_DRY_RUN_CODES = Set.of(
            "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR", "GITHUB_API_UNAVAILABLE",
            "GIT_STORE_FETCH_FAILED", "DRY_RUN_TIMEOUT");
    private static final Set<String> AUTOMATIC_RETRYABLE_DRY_RUN_CODES = Set.of(
            "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR", "GITHUB_API_UNAVAILABLE",
            "GIT_STORE_FETCH_FAILED", "DRY_RUN_TIMEOUT");

    private String reportFailureCode(DryRunEntity run) {
        Object value = run.getReport() == null ? null : run.getReport().get("failureCode");
        return value == null ? null : String.valueOf(value);
    }

    private String activeClaimKey(UUID projectId, UUID taskId, UUID repositoryId, String headCommit,
                                  String targetBranch, String targetCommit) {
        String raw = String.join(":", String.valueOf(projectId), String.valueOf(taskId), String.valueOf(repositoryId),
                headCommit, targetBranch, targetCommit);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private int retryDepth(DryRunEntity run) {
        int depth = 0;
        UUID parent = run.getRetryOfDryRunId();
        while (parent != null && ++depth <= MAX_DRY_RUN_RETRIES) {
            DryRunEntity ancestor = dryRunMapper.selectById(parent);
            parent = ancestor == null ? null : ancestor.getRetryOfDryRunId();
        }
        return depth;
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
