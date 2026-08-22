package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.MrPreflightRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MrPreflightRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;

import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * 统一 MR 自动预检编排器（MR_FIRST / DIFF_FIRST）。
 * <p>
 * MR_FIRST 在代码交付完成后自动为各仓库发起分支级预检申请；DIFF_FIRST 由用户点击“创建 MR”
 * 发起同一申请。两条入口都持久化 {@code mr_preflight_requests} 并复用
 * {@code TestRunService#createAutomaticDryRun}。独立成员 CQ+1 通过后自动调用幂等 MR 创建服务。
 * 外部 Worker/GitHub 调用均发生在事务提交后的异步线程，事件丢失时由定时补偿恢复。
 */
@Service
@Slf4j
public class MrFirstAutomationService {
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final DryRunMapper dryRuns;
    private final MergeRequestMapper mergeRequests;
    private final MrPreflightRequestMapper preflightRequests;
    private final MergeRequestService mrService;
    private final PreflightGateService preflightGates;
    private final MrPreflightService preflightService;
    /** 防止事件监听器与恢复调度器在同一进程内同时刷新同一个 Git Store。 */
    private final Map<String, Boolean> preflightInFlight = new ConcurrentHashMap<>();
    /** 外部预检失败时的短暂退避，避免 Worker/GitHub 故障形成重试风暴。 */
    private final Map<String, RetryState> preflightRetryStates = new ConcurrentHashMap<>();

    public MrFirstAutomationService(TaskMapper tasks, WorkspaceRepositoryMapper worktrees,
                                    ProjectRepositoryMapper repositories, DryRunMapper dryRuns,
                                    MergeRequestMapper mergeRequests, MrPreflightRequestMapper preflightRequests,
                                    MergeRequestService mrService,
                                    PreflightGateService preflightGates, MrPreflightService preflightService) {
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.dryRuns = dryRuns;
        this.mergeRequests = mergeRequests;
        this.preflightRequests = preflightRequests;
        this.mrService = mrService;
        this.preflightGates = preflightGates;
        this.preflightService = preflightService;
    }

    /** MR_FIRST 交付批次进入 WAITING_PREFLIGHT 后，为 Workspace 中每个仓库发起分支级预检申请。 */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPreflightRequested(MrFirstPreflightRequestedDomainEvent event) {
        startPreflightRequests(event.projectId(), event.taskId());
    }

    /** 独立 CQ+1 通过后自动创建对应仓库的真实 MR（MR_FIRST 与 DIFF_FIRST 共用）。 */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCqApproved(PreflightCqApprovedDomainEvent event) {
        createMergeRequest(event.projectId(), event.dryRunId());
    }

    /**
     * 进程重启或事件监听失败后的补偿：
     * <p>
     * 1. 未发起预检的 WAITING_PREFLIGHT MR_FIRST Task 重新发起分支级预检申请；
     * 2. 进行中的预检申请按当前 Dry Run / CQ 事实推进持久化状态；
     * 3. 已通过且独立 CQ+1 通过但尚未创建 MR 的 Dry Run 重新尝试幂等创建。
     */
    @Scheduled(fixedDelayString = "${qgents.mr-first.poll-delay-ms:15000}", initialDelayString = "${qgents.mr-first.initial-delay-ms:15000}")
    public void recover() {
        List<TaskEntity> waiting = tasks.selectList(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getDeliveryMode, "MR_FIRST")
                .eq(TaskEntity::getStatus, "WAITING_PREFLIGHT")
                .orderByAsc(TaskEntity::getUpdatedAt).last("LIMIT 20"));
        for (TaskEntity task : waiting) {
            startPreflightRequests(task.getProjectId(), task.getId());
        }
        List<MrPreflightRequestEntity> recoverable = preflightRequests.selectRecoverable(50);
        for (MrPreflightRequestEntity request : recoverable) {
            try {
                preflightService.reconcile(request);
            } catch (RuntimeException failure) {
                log.warn("preflight reconcile failed projectId={} preflightId={}: {}",
                        request.getProjectId(), request.getId(), failure.getMessage());
            }
        }
        List<DryRunEntity> passed = dryRuns.selectList(Wrappers.<DryRunEntity>lambdaQuery()
                .eq(DryRunEntity::getStatus, "PASSED")
                .isNotNull(DryRunEntity::getTaskId)
                .orderByAsc(DryRunEntity::getUpdatedAt).last("LIMIT 50"));
        for (DryRunEntity dryRun : passed) {
            if (hasNonMergedMr(dryRun)) continue;
            createMergeRequest(dryRun.getProjectId(), dryRun.getId());
        }
    }

    /**
     * 为 Task 的各仓库逐个发起分支级预检申请；重复调用由预检幂等上下文兜底。
     */
    private void startPreflightRequests(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())
                || task.getCreatedBy() == null || task.getWorkspaceId() == null) {
            return;
        }
        List<WorkspaceRepositoryEntity> values = worktrees.selectByWorkspace(task.getWorkspaceId());
        for (WorkspaceRepositoryEntity worktree : values) {
            String repositoryKey = repositoryKey(projectId, worktree);
            if (isInRetry(repositoryKey)) {
                log.debug("preflight request deferred during retry backoff projectId={} taskId={} repositoryId={}",
                        projectId, taskId, worktree.getProjectRepositoryId());
                continue;
            }
            if (preflightInFlight.putIfAbsent(repositoryKey, Boolean.TRUE) != null) {
                log.debug("preflight request deduplicated while in flight projectId={} taskId={} repositoryId={}",
                        projectId, taskId, worktree.getProjectRepositoryId());
                continue;
            }
            try {
                preflightService.requestPreflight(projectId, task.getCreatedBy(), taskId,
                        worktree.getProjectRepositoryId(), null);
                preflightRetryStates.remove(repositoryKey);
                log.info("preflight requested projectId={} taskId={} repositoryId={}",
                        projectId, taskId, worktree.getProjectRepositoryId());
            } catch (RuntimeException failure) {
                if (isNoChangesFailure(failure)) {
                    tasks.failMrPreflightNoChanges(projectId, taskId);
                    log.warn("preflight stopped because source and target have no changes "
                                    + "projectId={} taskId={} repositoryId={}",
                            projectId, taskId, worktree.getProjectRepositoryId());
                    // 这是任务级终态，当前 Workspace 的其他仓库不应继续申请预检。
                    break;
                }
                // 外部基础设施失败暂不改变 Task，但要退避后再重试，避免每轮调度重复打 Worker。
                scheduleRetry(repositoryKey);
                String failureCode = failure instanceof ApiException api ? api.code() : failure.getClass().getSimpleName();
                String failureStatus = failure instanceof ApiException api && api.status() != null
                        ? api.status().toString() : "";
                log.warn("preflight request failed projectId={} taskId={} repositoryId={} exceptionType={} "
                                + "failureCode={} status={} message={}",
                        projectId, taskId, worktree.getProjectRepositoryId(), failure.getClass().getName(),
                        failureCode, failureStatus,
                        ExecutionContentSanitizer.sanitizeDiagnosticDetail(failure.getMessage()));
            } finally {
                preflightInFlight.remove(repositoryKey);
            }
        }
    }

    private String repositoryKey(UUID projectId, WorkspaceRepositoryEntity worktree) {
        return projectId + ":" + worktree.getProjectRepositoryId() + ":"
                + (worktree.getSourceBranch() == null ? "" : worktree.getSourceBranch());
    }

    private boolean isInRetry(String repositoryKey) {
        RetryState state = preflightRetryStates.get(repositoryKey);
        return state != null && Instant.now().isBefore(state.nextAttemptAt());
    }

    private void scheduleRetry(String repositoryKey) {
        preflightRetryStates.compute(repositoryKey, (key, previous) -> {
            int failures = previous == null ? 1 : previous.failures() + 1;
            long delaySeconds = Math.min(MAX_RETRY_DELAY.toSeconds(),
                    INITIAL_RETRY_DELAY.toSeconds() << Math.min(failures - 1, 10));
            return new RetryState(failures, Instant.now().plusSeconds(delaySeconds));
        });
    }

    private boolean isNoChangesFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ApiException apiException
                    && "MR_NO_CHANGES".equals(apiException.code())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("MR_NO_CHANGES")) {
                return true;
            }
        }
        return false;
    }

    private record RetryState(int failures, Instant nextAttemptAt) {
    }

    private void createMergeRequest(UUID projectId, UUID dryRunId) {
        DryRunEntity dryRun = dryRuns.selectById(dryRunId);
        if (dryRun == null || !projectId.equals(dryRun.getProjectId()) || !"PASSED".equals(dryRun.getStatus())) {
            return;
        }
        TaskEntity task = tasks.selectById(dryRun.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())
                || !isPreflightActionable(task) || task.getCreatedBy() == null) {
            return;
        }
        // 多仓库交付必须先确认所有仓库的当前 source/target、Dry Run 和独立 CQ+1，
        // 任一仓库未完成时只保留预检状态，不提前创建局部 MR。
        for (WorkspaceRepositoryEntity worktree : worktrees.selectByWorkspace(task.getWorkspaceId())) {
            ProjectRepositoryEntity repository = repositories.selectById(worktree.getProjectRepositoryId());
            String branch = worktree.getBaseRef();
            if (branch == null || branch.isBlank()) branch = repository == null ? null : repository.getDefaultBranch();
            if (branch == null || branch.isBlank()) return;
            try {
                if (!"PASSED".equals(preflightGates.get(projectId, task.getId(), worktree.getProjectRepositoryId(),
                        branch, task.getCreatedBy()).getStatus())) return;
            } catch (RuntimeException ignored) {
                return;
            }
        }
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(task.getId());
        request.setRepositoryId(dryRun.getProjectRepositoryId());
        request.setTargetBranch(dryRun.getTargetBranch());
        request.setTitle(task.getTitle() == null || task.getTitle().isBlank() ? "Qgents Task " + task.getId() : task.getTitle());
        try {
            MergeRequestSummaryResponse summary = mrService.create(projectId, task.getCreatedBy(), request);
            markPreflightMrCreated(projectId, dryRunId, summary == null ? null : summary.getId());
            log.info("merge request ensured projectId={} taskId={} dryRunId={} repositoryId={} mrId={}",
                    projectId, task.getId(), dryRunId, dryRun.getProjectRepositoryId(),
                    summary == null ? null : summary.getId());
        } catch (RuntimeException failure) {
            // MR 创建本身已有 operation lease 和幂等键；这里保留预检状态，补偿任务可重试。
            log.warn("merge request creation deferred projectId={} taskId={} dryRunId={}: {}",
                    projectId, task.getId(), dryRunId, failure.getMessage());
        }
    }

    /**
     * 真实 MR 创建成功后，把关联的分支级预检请求推进到 MR_CREATED 并回填真实 MR ID。
     */
    private void markPreflightMrCreated(UUID projectId, UUID dryRunId, String mergeRequestId) {
        if (mergeRequestId == null) {
            return;
        }
        preflightService.markMrCreated(projectId, dryRunId, UUID.fromString(mergeRequestId));
    }

    private boolean hasNonMergedMr(DryRunEntity dryRun) {
        TaskEntity task = tasks.selectById(dryRun.getTaskId());
        if (task == null || task.getWorkspaceId() == null) return false;
        WorkspaceRepositoryEntity worktree = worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> dryRun.getProjectRepositoryId().equals(value.getProjectRepositoryId())).findFirst().orElse(null);
        if (worktree == null || worktree.getSourceBranch() == null) return false;
        return mergeRequests.selectOne(Wrappers.<qg.qgent.entity.MergeRequestEntity>lambdaQuery()
                .eq(qg.qgent.entity.MergeRequestEntity::getProjectRepositoryId, dryRun.getProjectRepositoryId())
                .eq(qg.qgent.entity.MergeRequestEntity::getSourceBranch, worktree.getSourceBranch())
                .ne(qg.qgent.entity.MergeRequestEntity::getStatus, "MERGED")
                .last("LIMIT 1")) != null;
    }

    private boolean isPreflightActionable(TaskEntity task) {
        return ("MR_FIRST".equals(task.getDeliveryMode())
                && ("WAITING_PREFLIGHT".equals(task.getStatus()) || "SUCCEEDED".equals(task.getStatus())))
                || ("DIFF_FIRST".equals(task.getDeliveryMode()) && "SUCCEEDED".equals(task.getStatus()));
    }
}
