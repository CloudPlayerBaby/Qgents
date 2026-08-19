package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;

import java.util.List;
import java.util.UUID;

/**
 * MR_FIRST 的自动预检编排器。
 * <p>
 * 代码交付完成后自动创建各仓库 Dry Run；独立成员 CQ+1 通过后自动调用已有的幂等 MR
 * 创建服务。外部 Worker/GitHub 调用均发生在事务提交后的异步线程，事件丢失时由定时补偿恢复。
 */
@Service
@Slf4j
public class MrFirstAutomationService {
    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final DryRunMapper dryRuns;
    private final MergeRequestMapper mergeRequests;
    private final TestRunService testRuns;
    private final MergeRequestService mrService;
    private final PreflightGateService preflightGates;

    public MrFirstAutomationService(TaskMapper tasks, WorkspaceRepositoryMapper worktrees,
                                    ProjectRepositoryMapper repositories, DryRunMapper dryRuns,
                                    MergeRequestMapper mergeRequests, TestRunService testRuns,
                                    MergeRequestService mrService, PreflightGateService preflightGates) {
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.dryRuns = dryRuns;
        this.mergeRequests = mergeRequests;
        this.testRuns = testRuns;
        this.mrService = mrService;
        this.preflightGates = preflightGates;
    }

    /** 交付批次进入 WAITING_PREFLIGHT 后，为 Workspace 中每个仓库启动 Dry Run。 */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPreflightRequested(MrFirstPreflightRequestedDomainEvent event) {
        startDryRuns(event.projectId(), event.taskId());
    }

    /** 独立 CQ+1 通过后自动创建对应仓库的真实 MR。 */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCqApproved(PreflightCqApprovedDomainEvent event) {
        createMergeRequest(event.projectId(), event.dryRunId());
    }

    /**
     * 进程重启或事件监听失败后的补偿：未创建 Dry Run 的 WAITING_PREFLIGHT Task 重新发起预检，
     * 已通过但尚未创建 MR 的 Dry Run 重新尝试幂等创建。已有未合并 MR 的分支由 MR 服务门禁保护。
     */
    @Scheduled(fixedDelayString = "${qgents.mr-first.poll-delay-ms:15000}", initialDelayString = "${qgents.mr-first.initial-delay-ms:15000}")
    public void recover() {
        List<TaskEntity> waiting = tasks.selectList(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getDeliveryMode, "MR_FIRST")
                .eq(TaskEntity::getStatus, "WAITING_PREFLIGHT")
                .orderByAsc(TaskEntity::getUpdatedAt).last("LIMIT 20"));
        for (TaskEntity task : waiting) {
            startDryRuns(task.getProjectId(), task.getId());
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

    private void startDryRuns(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId()) || !"MR_FIRST".equals(task.getDeliveryMode())
                || !"WAITING_PREFLIGHT".equals(task.getStatus()) || task.getWorkspaceId() == null) {
            return;
        }
        List<WorkspaceRepositoryEntity> values = worktrees.selectByWorkspace(task.getWorkspaceId());
        for (WorkspaceRepositoryEntity worktree : values) {
            try {
                ProjectRepositoryEntity repository = repositories.selectById(worktree.getProjectRepositoryId());
                String targetBranch = worktree.getBaseRef();
                if (targetBranch == null || targetBranch.isBlank()) {
                    targetBranch = repository == null ? null : repository.getDefaultBranch();
                }
                if (targetBranch == null || targetBranch.isBlank()) {
                    log.warn("mr-first dry run skipped because target branch is missing projectId={} taskId={} repositoryId={}",
                            projectId, taskId, worktree.getProjectRepositoryId());
                    continue;
                }
                DryRunResponse response = testRuns.createAutomaticDryRun(projectId, taskId,
                        worktree.getProjectRepositoryId(), targetBranch);
                if (response != null) {
                    log.info("mr-first dry run accepted projectId={} taskId={} repositoryId={} dryRunId={} status={}",
                            projectId, taskId, worktree.getProjectRepositoryId(), response.getId(), response.getStatus());
                }
            } catch (RuntimeException failure) {
                // Dry Run 创建失败不应把已 push 的 Task 标成开发失败；恢复调度器稍后重试。
                log.warn("mr-first dry run dispatch failed projectId={} taskId={} repositoryId={}: {}",
                        projectId, taskId, worktree.getProjectRepositoryId(), failure.getMessage());
            }
        }
    }

    private void createMergeRequest(UUID projectId, UUID dryRunId) {
        DryRunEntity dryRun = dryRuns.selectById(dryRunId);
        if (dryRun == null || !projectId.equals(dryRun.getProjectId()) || !"PASSED".equals(dryRun.getStatus())) {
            return;
        }
        TaskEntity task = tasks.selectById(dryRun.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId()) || !"MR_FIRST".equals(task.getDeliveryMode())
                || !"WAITING_PREFLIGHT".equals(task.getStatus()) || task.getCreatedBy() == null) {
            return;
        }
        // 多仓库交付必须先确认所有仓库的当前 source/target、Dry Run 和独立 CQ+1，
        // 任一仓库未完成时只保留 WAITING_PREFLIGHT，不提前创建局部 MR。
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
            mrService.create(projectId, task.getCreatedBy(), request);
            log.info("mr-first merge request ensured projectId={} taskId={} dryRunId={} repositoryId={}",
                    projectId, task.getId(), dryRunId, dryRun.getProjectRepositoryId());
        } catch (RuntimeException failure) {
            // MR 创建本身已有 operation lease 和幂等键；这里保留 WAITING_PREFLIGHT，补偿任务可重试。
            log.warn("mr-first merge request creation deferred projectId={} taskId={} dryRunId={}: {}",
                    projectId, task.getId(), dryRunId, failure.getMessage());
        }
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
}
