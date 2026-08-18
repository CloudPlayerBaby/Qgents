package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepDependencyMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.TaskStepRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.AgentDispatcher;
import qg.qgent.orchestration.DeliveryMode;
import qg.qgent.orchestration.DeliveryModeDecider;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.mapper.RepositoryBranchConfigMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将已完成的 Planner 结果一次性固化为 Task 的正式执行步骤。
 * Agent 调用已在事务外完成；本服务仅执行数据库写入，并以 Task 行锁和 materialized 标记保证幂等。
 * 步骤的 Agent 选用统一委托给调度 Agent（{@link AgentDispatcher}），本服务不持有任何 Agent 选择逻辑。
 */
@Service
public class TaskPlanMaterializationService {

    private final TaskMapper tasks;
    private final TaskStepMapper steps;
    private final TaskStepDependencyMapper dependencies;
    private final TaskStepRepositoryMapper scopes;
    private final WorkspaceRepositoryMapper worktrees;
    private final TaskExecutionArtifactService artifacts;
    private final EventService events;
    private final AgentDispatcher agentDispatcher;
    private final DeliveryModeDecider deliveryModeDecider;
    private final RepositoryBranchConfigMapper branchConfigMapper;

    public TaskPlanMaterializationService(TaskMapper tasks, TaskStepMapper steps, TaskStepDependencyMapper dependencies,
                                          TaskStepRepositoryMapper scopes, WorkspaceRepositoryMapper worktrees,
                                          TaskExecutionArtifactService artifacts, EventService events,
                                          AgentDispatcher agentDispatcher, DeliveryModeDecider deliveryModeDecider,
                                          RepositoryBranchConfigMapper branchConfigMapper) {
        this.tasks = tasks;
        this.steps = steps;
        this.dependencies = dependencies;
        this.scopes = scopes;
        this.worktrees = worktrees;
        this.artifacts = artifacts;
        this.events = events;
        this.agentDispatcher = agentDispatcher;
        this.deliveryModeDecider = deliveryModeDecider;
        this.branchConfigMapper = branchConfigMapper;
    }

    /**
     * 创建唯一的 Planner bootstrap Step。无预置步骤时只创建该步骤，绝不预建开发/测试/审查模板。
     */
    @Transactional
    public TaskStepEntity ensurePlannerStep(TaskEntity task) {
        TaskEntity locked = tasks.selectByIdForUpdate(task.getId());
        if (locked == null) {
            throw new IllegalStateException("task disappeared while ensuring planner step");
        }
        List<TaskStepEntity> existing = steps.selectByTaskForUpdate(task.getId());
        return existing.stream().filter(step -> "PLANNER".equals(step.getRole())).findFirst().orElseGet(() -> {
            TaskStepEntity planner = new TaskStepEntity();
            planner.setId(UuidV7.next());
            planner.setTaskId(task.getId());
            // 手工步骤已存在却漏配 Planner 时，不改写用户已冻结的序号；正式图本来会排除 Planner。
            planner.setSequenceNo(existing.stream().map(TaskStepEntity::getSequenceNo).filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo).orElse(0) + 1);
            planner.setTitle("Plan");
            planner.setInstruction("分析需求并制定实现计划");
            planner.setRole("PLANNER");
            planner.setAcceptanceCriteria("产出可执行且可冻结的实现计划");
            planner.setRequiredCapabilities(List.of("planning"));
            planner.setStatus("PENDING");
            planner.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            planner.setUpdatedAt(planner.getCreatedAt());
            steps.insert(planner);
            registerStepEvent(task, planner);
            return planner;
        });
    }

    /**
     * 计划成功后的唯一物化入口。已有手工非 Planner 步骤时保留其清单，仅保存 Task 级 Plan 产物。
     * 物化事务内同时定型交付模式（优先级：用户显式 &gt; Planner 判定 &gt; 硬规则兜底），执行期不变。
     */
    @Transactional
    public List<TaskStepEntity> materialize(TaskEntity task, PlanResult plan) {
        TaskEntity locked = tasks.selectByIdForUpdate(task.getId());
        if (locked == null) {
            throw new IllegalStateException("task disappeared while materializing plan");
        }
        List<TaskStepEntity> existing = steps.selectByTaskForUpdate(task.getId());
        if (locked.getPlanMaterializedAt() != null) {
            return existing;
        }
        TaskStepEntity planner = existing.stream().filter(step -> "PLANNER".equals(step.getRole())).findFirst()
                .orElseThrow(() -> new IllegalStateException("planner bootstrap step is missing"));
        boolean manualPlan = existing.stream().anyMatch(step -> !"PLANNER".equals(step.getRole()));
        List<WorkspaceRepositoryEntity> worktreeList = worktrees.selectByWorkspace(locked.getWorkspaceId());
        DeliveryDecision decision = resolveDeliveryMode(locked, plan, worktreeList);
        locked.setDeliveryMode(decision.mode());
        locked.setDeliveryReason(decision.reason());
        artifacts.createPlan(locked, planSummary(plan, decision));
        if (!manualPlan) {
            createGeneratedSteps(locked, plan, planner, worktreeList);
        }
        planner.setStatus("SUCCEEDED");
        planner.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        steps.updateById(planner);
        locked.setPlanMaterializedAt(LocalDateTime.now(ZoneOffset.UTC));
        locked.setUpdatedAt(locked.getPlanMaterializedAt());
        tasks.updateById(locked);
        registerStepEvent(locked, planner);
        return steps.selectByTaskForUpdate(locked.getId());
    }

    private void createGeneratedSteps(TaskEntity task, PlanResult plan, TaskStepEntity planner,
                                      List<WorkspaceRepositoryEntity> worktreeList) {
        List<UUID> repositories = worktreeList.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        List<TaskStepEntity> created = new ArrayList<>();
        UUID previous = planner.getId();
        int sequence = planner.getSequenceNo() + 1;
        for (PlanResult.ImplementationStep item : plan.getImplementationSteps()) {
            TaskStepEntity step = step(task, sequence++, item.getTitle(), developerInstruction(plan, item), "DEVELOPER",
                    item.getRequiredCapabilities(), "完成 " + item.getTitle() + " 并通过相关自检",
                    item.getSuggestedAgentId());
            steps.insert(step);
            dependencies.insertLink(step.getId(), previous);
            insertScopes(step.getId(), repositoriesForStep(item, worktreeList), "WRITE");
            created.add(step);
            previous = step.getId();
        }
        TaskStepEntity tester = step(task, sequence++, "Verify", plan.getTestPlan(), "TESTER", List.of(),
                "执行计划测试并记录真实结果", null);
        steps.insert(tester);
        dependencies.insertLink(tester.getId(), previous);
        insertScopes(tester.getId(), repositories, "READ");
        created.add(tester);
        TaskStepEntity reviewer = step(task, sequence, "Review", "审查本次改动是否符合需求、质量与安全要求", "REVIEWER",
                List.of(), "完成独立代码审查", null);
        steps.insert(reviewer);
        dependencies.insertLink(reviewer.getId(), tester.getId());
        insertScopes(reviewer.getId(), repositories, "READ");
        created.add(reviewer);
        created.forEach(step -> registerStepEvent(task, step));
    }

    /**
     * 根据 Planner 输出的工作区相对路径收敛开发步骤的仓库范围。
     *
     * Planner 文件路径通常带有 worktree 前缀（例如 repo-1/README.md）。
     * 旧计划可能没有此前缀，或引用了需要新建的文件；此时保留全仓库范围作为兼容回退，
     * 避免把一个无法可靠归属的步骤错误限制到某个仓库。
     */
    private List<UUID> repositoriesForStep(PlanResult.ImplementationStep item,
                                            List<WorkspaceRepositoryEntity> worktreeList) {
        List<UUID> all = worktreeList.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        if (worktreeList.size() <= 1 || item == null || item.getFiles() == null || item.getFiles().isEmpty()) {
            return all;
        }
        List<UUID> matched = worktreeList.stream()
                .filter(worktree -> item.getFiles().stream().anyMatch(file -> belongsToWorktree(file, worktree)))
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId)
                .distinct()
                .toList();
        return matched.isEmpty() ? all : matched;
    }

    private boolean belongsToWorktree(String file, WorkspaceRepositoryEntity worktree) {
        if (file == null || file.isBlank() || worktree.getWorkspacePath() == null
                || worktree.getWorkspacePath().isBlank()) {
            return false;
        }
        String path = file.replace('\\', '/');
        String prefix = worktree.getWorkspacePath().replace('\\', '/').replaceAll("^/+|/+$", "");
        return !prefix.isBlank() && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private TaskStepEntity step(TaskEntity task, int sequence, String title, String instruction, String role,
                                List<String> requiredCapabilities, String acceptance, UUID suggestedAgentId) {
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UuidV7.next());
        step.setTaskId(task.getId());
        step.setSequenceNo(sequence);
        step.setTitle(title);
        step.setInstruction(instruction);
        step.setRole(role);
        step.setRequiredCapabilities(requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities));
        step.setAssignedAgentId(resolveAgent(task, role, step.getRequiredCapabilities(), suggestedAgentId));
        step.setAcceptanceCriteria(acceptance);
        step.setStatus("PENDING");
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        step.setUpdatedAt(step.getCreatedAt());
        return step;
    }

    /**
     * 选人：Plan 建议的 {@code suggestedAgentId} 作为先验交给调度 Agent，仍经候选池校验与
     * 确定性兜底（池外/非法建议不采信，退回自动选择），绝不绕过安全网。
     */
    private UUID resolveAgent(TaskEntity task, String role, List<String> requiredCapabilities,
                              UUID suggestedAgentId) {
        return agentDispatcher.dispatch(task, role, requiredCapabilities, suggestedAgentId)
                .map(agent -> agent.getId())
                .orElse(null);
    }

    private void insertScopes(UUID stepId, List<UUID> repositories, String mode) {
        repositories.forEach(repositoryId -> scopes.insertLink(stepId, repositoryId, mode));
    }

    private String developerInstruction(PlanResult plan, PlanResult.ImplementationStep item) {
        return "实现目标：" + String.join("；", plan.getObjectives()) + "\n步骤：" + item.getTitle()
                + "\n涉及文件：" + String.join(", ", item.getFiles())
                + (item.getDescription() == null || item.getDescription().isBlank() ? "" : "\n说明：" + item.getDescription());
    }

    private Map<String, Object> planSummary(PlanResult plan, DeliveryDecision decision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskUnderstanding", plan.getTaskUnderstanding());
        result.put("objectives", plan.getObjectives());
        result.put("steps", plan.getImplementationSteps().stream().map(item -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("title", item.getTitle());
            step.put("files", item.getFiles());
            step.put("description", item.getDescription());
            step.put("requiredCapabilities", item.getRequiredCapabilities());
            step.put("suggestedAgentId", item.getSuggestedAgentId() == null ? null : item.getSuggestedAgentId().toString());
            return step;
        }).toList());
        result.put("testPlan", plan.getTestPlan());
        result.put("risks", plan.getRisks());
        result.put("deliveryMode", decision.mode());
        result.put("scaleReason", plan.getScaleReason());
        result.put("deliveryReason", decision.reason());
        return result;
    }

    /**
     * 交付模式定型：用户显式指定（或续作沿用）优先，其次 Planner 判定，最后硬规则兜底。
     * 判定依据与理由随 Plan 物化写入 Task，执行期不再变化。
     */
    private DeliveryDecision resolveDeliveryMode(TaskEntity task, PlanResult plan,
                                                 List<WorkspaceRepositoryEntity> worktreeList) {
        if (DeliveryMode.isValid(task.getDeliveryMode())) {
            return new DeliveryDecision(task.getDeliveryMode(), "用户指定或沿用源任务");
        }
        if (DeliveryMode.isValid(plan.getDeliveryMode())) {
            String reason = plan.getScaleReason() == null || plan.getScaleReason().isBlank()
                    ? "由 Planner 判定" : plan.getScaleReason();
            return new DeliveryDecision(plan.getDeliveryMode(), reason);
        }
        boolean hasRequiredChecks = targetBranchHasRequiredChecks(worktreeList);
        int repositoryCount = worktreeList.size();
        int developerStepCount = plan.getImplementationSteps().size();
        String mode = deliveryModeDecider.decide(repositoryCount, developerStepCount, hasRequiredChecks);
        return new DeliveryDecision(mode, ruleReason(mode, repositoryCount, developerStepCount, hasRequiredChecks));
    }

    /**
     * 目标分支是否配置了 requiredChecks 质量门禁：按工作区各 worktree 的基分支（baseCommit）匹配分支配置。
     * baseCommit 为提交 SHA（非分支名）时无法匹配分支策略，按无门禁处理。
     */
    private boolean targetBranchHasRequiredChecks(List<WorkspaceRepositoryEntity> worktreeList) {
        if (worktreeList.isEmpty()) {
            return false;
        }
        List<UUID> repositoryIds = worktreeList.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        // 用字符串列名 QueryWrapper：避免 lambda 缓存依赖，纯单测环境（mock Mapper）下亦可执行。
        List<RepositoryBranchConfigEntity> configs = branchConfigMapper.selectList(
                Wrappers.<RepositoryBranchConfigEntity>query()
                        .in("project_repository_id", repositoryIds));
        return configs.stream().anyMatch(config -> config.getRequiredChecks() != null
                && !config.getRequiredChecks().isEmpty()
                && worktreeList.stream().anyMatch(w -> config.getBranchName().equals(baselineBranch(w))));
    }

    /**
     * worktree 的基线分支名：优先不可变 base_ref；兼容迁移前旧数据，回退 base_commit 中的
     * 分支名形态值。base_commit 被 provision 回填为 SHA 后该回退自然失效，属预期。
     */
    private String baselineBranch(WorkspaceRepositoryEntity worktree) {
        if (worktree.getBaseRef() != null && !worktree.getBaseRef().isBlank()) {
            return worktree.getBaseRef();
        }
        return isBranchName(worktree.getBaseCommit()) ? worktree.getBaseCommit() : null;
    }

    private boolean isBranchName(String value) {
        return value != null && !value.isBlank() && !value.matches("[0-9a-fA-F]{40}");
    }

    private String ruleReason(String mode, int repositoryCount, int developerStepCount, boolean hasRequiredChecks) {
        if (DeliveryMode.MR_FIRST.equals(mode)) {
            if (repositoryCount > 1) {
                return "涉及 " + repositoryCount + " 个仓库，按规则判定 MR_FIRST";
            }
            if (developerStepCount > 2) {
                return "开发步骤 " + developerStepCount + " 个，按规则判定 MR_FIRST";
            }
            if (hasRequiredChecks) {
                return "目标分支配置了质量门禁，按规则判定 MR_FIRST";
            }
            return "按规则判定 MR_FIRST";
        }
        return "按规则判定 DIFF_FIRST";
    }

    /**
     * 交付模式判定结果：模式 + 判定理由（随 Task 落库，供看板/卡片展示）。
     */
    private record DeliveryDecision(String mode, String reason) {
    }

    private void registerStepEvent(TaskEntity task, TaskStepEntity step) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                events.publish(task.getProjectId(), task.getRequirementGroupId(), "task-step.updated", step.getId().toString(),
                        TaskEventPayloads.taskStepUpdated(task.getProjectId(), step));
            }
        });
    }
}
