package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.event.DryRunConflictCandidateDomainEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并冲突自动解决的编排器：MR 前 Dry Run 因确定性合并冲突失败时，自动派发一条
 * "解决冲突"续跑任务，把冲突文件清单注入需求，让 Coding Agent 合并目标分支并重推。
 * <p>
 * 事件监听负责及时触发，定时补偿负责事件丢失后的恢复；两条路径都会经过同一组守护，
 * 保证同一冲突只被处理一次：有活动续跑（含人工续跑）不重复派、分支已存在未合并 MR 交人工、
 * 冲突已过期（head 已推进）不针对旧 head 派、续跑总数达到上限后改人工。
 */
@Service
@Slf4j
public class ConflictResolutionAutomationService {
    private final DryRunMapper dryRuns;
    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final MergeRequestMapper mergeRequests;
    private final TaskService taskService;
    private final int maxContinuationsPerTask;
    /** 每条冲突 Dry Run 至多处理一次，防止事件风暴与补偿重放重复派发；重启后清空由守护兜底。 */
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    public ConflictResolutionAutomationService(DryRunMapper dryRuns, TaskMapper tasks,
                                               WorkspaceRepositoryMapper worktrees, MergeRequestMapper mergeRequests,
                                               TaskService taskService,
                                               @Value("${qgents.conflict-resolution.max-continuations-per-task:3}") int maxContinuationsPerTask) {
        this.dryRuns = dryRuns;
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.mergeRequests = mergeRequests;
        this.taskService = taskService;
        this.maxContinuationsPerTask = maxContinuationsPerTask;
    }

    /** 冲突 Dry Run 落库后及时派发解决续跑。 */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConflict(DryRunConflictCandidateDomainEvent event) {
        maybeSpawnContinuation(event.projectId(), event.dryRunId());
    }

    /**
     * 事件丢失或监听失败后的补偿：扫描 MR_FIRST 且处于 WAITING_PREFLIGHT 的 Task，
     * 对当前 head 上确定性冲突的 Dry Run 重新评估是否派发解决续跑。
     */
    @Scheduled(fixedDelayString = "${qgents.conflict-resolution.poll-delay-ms:15000}",
            initialDelayString = "${qgents.mr-first.initial-delay-ms:15000}")
    public void recover() {
        List<TaskEntity> waiting = tasks.selectList(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getDeliveryMode, "MR_FIRST")
                .eq(TaskEntity::getStatus, "WAITING_PREFLIGHT")
                .orderByAsc(TaskEntity::getUpdatedAt).last("LIMIT 20"));
        for (TaskEntity task : waiting) {
            if (task.getWorkspaceId() == null) {
                continue;
            }
            for (WorkspaceRepositoryEntity worktree : worktrees.selectByWorkspace(task.getWorkspaceId())) {
                DryRunEntity latest = latestDryRun(task.getProjectId(), task.getId(),
                        worktree.getProjectRepositoryId(), worktree.getHeadCommit());
                if (latest != null && "FAILED".equals(latest.getStatus())
                        && TestRunService.isDeterministicConflict(latest)) {
                    maybeSpawnContinuation(task.getProjectId(), latest.getId());
                }
            }
        }
    }

    /**
     * 守护链全部通过后为冲突 Dry Run 创建解决续跑任务；任一守卫不满足即静默跳过。
     */
    private void maybeSpawnContinuation(UUID projectId, UUID dryRunId) {
        if (!processed.add(dryRunId.toString())) {
            return;
        }
        try {
            doSpawn(projectId, dryRunId);
        } catch (RuntimeException failure) {
            log.warn("conflict continuation spawn failed projectId={} dryRunId={}: {}",
                    projectId, dryRunId, failure.getMessage());
        }
    }

    private void doSpawn(UUID projectId, UUID dryRunId) {
        DryRunEntity dryRun = dryRuns.selectById(dryRunId);
        if (dryRun == null || !projectId.equals(dryRun.getProjectId()) || dryRun.getTaskId() == null
                || !"FAILED".equals(dryRun.getStatus())
                || !TestRunService.isDeterministicConflict(dryRun)) {
            return;
        }
        TaskEntity task = tasks.selectById(dryRun.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId()) || task.getWorkspaceId() == null
                || !"MR_FIRST".equals(task.getDeliveryMode())
                || !"WAITING_PREFLIGHT".equals(task.getStatus())) {
            return;
        }
        WorkspaceRepositoryEntity worktree = worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> dryRun.getProjectRepositoryId().equals(value.getProjectRepositoryId()))
                .findFirst().orElse(null);
        if (worktree == null || dryRun.getHeadCommit() == null
                || !dryRun.getHeadCommit().equalsIgnoreCase(worktree.getHeadCommit())) {
            // 冲突已过期：head 已推进，新的 Dry Run 会为新的 head 建立。
            return;
        }
        if (hasNonMergedMr(dryRun.getProjectRepositoryId(), worktree.getSourceBranch())) {
            // 分支已进入 MR 评审生命周期，交人工处理。
            return;
        }
        if (hasActiveContinuation(task.getId())) {
            return;
        }
        if (countContinuations(task.getId()) >= maxContinuationsPerTask) {
            log.warn("conflict continuation cap reached taskId={} max={}", task.getId(), maxContinuationsPerTask);
            return;
        }
        TaskCreateRequest request = buildRequest(task, worktree, dryRun);
        taskService.create(projectId, task.getCreatedBy(), request);
        log.info("conflict continuation spawned projectId={} taskId={} dryRunId={} repositoryId={} head={}",
                projectId, task.getId(), dryRunId, dryRun.getProjectRepositoryId(), dryRun.getHeadCommit());
    }

    private TaskCreateRequest buildRequest(TaskEntity task, WorkspaceRepositoryEntity worktree, DryRunEntity dryRun) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(task.getRequirementGroupId());
        request.setContinuationOfTaskId(task.getId());
        request.setWorkspaceId(task.getWorkspaceId());
        String title = task.getTitle() == null ? "解决合并冲突" : task.getTitle() + " [解决冲突]";
        request.setTitle(title.length() <= 255 ? title : title.substring(0, 255));
        request.setDeliveryMode(task.getDeliveryMode());
        request.setBaseRef(worktree.getBaseRef());
        request.setRequirement(buildRequirement(worktree, dryRun));
        return request;
    }

    private String buildRequirement(WorkspaceRepositoryEntity worktree, DryRunEntity dryRun) {
        String target = dryRun.getTargetBranch() == null ? worktree.getBaseRef() : dryRun.getTargetBranch();
        StringBuilder builder = new StringBuilder();
        builder.append("当前分支与目标分支 ").append(target == null ? "" : target)
                .append(" 存在合并冲突。请将目标分支合并进当前分支，解决所有冲突后重新推送，等待 MR 前预检通过。\n");
        builder.append("冲突来源 headCommit=").append(dryRun.getHeadCommit())
                .append("，目标 resolvedTargetCommit=").append(dryRun.getResolvedTargetCommit()).append("。\n");
        builder.append("冲突文件：\n");
        List<String> conflicts = conflicts(dryRun);
        if (conflicts.isEmpty()) {
            builder.append("- 未提供文件清单，请运行 git status 查看冲突\n");
        } else {
            conflicts.stream().limit(50).forEach(path -> builder.append("- ").append(path).append('\n'));
        }
        return builder.toString();
    }

    private List<String> conflicts(DryRunEntity dryRun) {
        Map<String, Object> report = dryRun.getReport();
        Object value = report == null ? null : report.get("conflicts");
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    private boolean hasNonMergedMr(UUID repositoryId, String sourceBranch) {
        if (repositoryId == null || sourceBranch == null || sourceBranch.isBlank()) {
            return false;
        }
        return mergeRequests.selectCount(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .eq(MergeRequestEntity::getSourceBranch, sourceBranch)
                .ne(MergeRequestEntity::getStatus, "MERGED")) > 0;
    }

    private boolean hasActiveContinuation(UUID taskId) {
        return tasks.selectCount(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getContinuationOfTaskId, taskId)
                .in(TaskEntity::getStatus, List.of("PLANNING", "PENDING", "RUNNING"))) > 0;
    }

    private long countContinuations(UUID taskId) {
        return tasks.selectCount(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getContinuationOfTaskId, taskId));
    }

    private DryRunEntity latestDryRun(UUID projectId, UUID taskId, UUID repositoryId, String headCommit) {
        if (headCommit == null || headCommit.isBlank()) {
            return null;
        }
        return dryRuns.selectOne(Wrappers.<DryRunEntity>lambdaQuery()
                .eq(DryRunEntity::getProjectId, projectId)
                .eq(DryRunEntity::getTaskId, taskId)
                .eq(DryRunEntity::getProjectRepositoryId, repositoryId)
                .eq(DryRunEntity::getHeadCommit, headCommit)
                .orderByDesc(DryRunEntity::getCreatedAt).last("LIMIT 1"));
    }
}
