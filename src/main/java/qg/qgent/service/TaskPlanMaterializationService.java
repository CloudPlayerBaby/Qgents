package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepDependencyMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.TaskStepRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.AgentMatchDecider;
import qg.qgent.orchestration.result.PlanResult;

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
 */
@Service
public class TaskPlanMaterializationService {

    private final TaskMapper tasks;
    private final TaskStepMapper steps;
    private final TaskStepDependencyMapper dependencies;
    private final TaskStepRepositoryMapper scopes;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectMapper projects;
    private final AgentMapper agents;
    private final TaskExecutionArtifactService artifacts;
    private final EventService events;
    private final AgentMatchDecider agentMatchDecider;

    public TaskPlanMaterializationService(TaskMapper tasks, TaskStepMapper steps, TaskStepDependencyMapper dependencies,
                                          TaskStepRepositoryMapper scopes, WorkspaceRepositoryMapper worktrees,
                                          ProjectMapper projects, AgentMapper agents,
                                          TaskExecutionArtifactService artifacts, EventService events,
                                          AgentMatchDecider agentMatchDecider) {
        this.tasks = tasks;
        this.steps = steps;
        this.dependencies = dependencies;
        this.scopes = scopes;
        this.worktrees = worktrees;
        this.projects = projects;
        this.agents = agents;
        this.artifacts = artifacts;
        this.events = events;
        this.agentMatchDecider = agentMatchDecider;
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
        artifacts.createPlan(locked, planSummary(plan));
        if (!manualPlan) {
            createGeneratedSteps(locked, plan, planner);
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

    private void createGeneratedSteps(TaskEntity task, PlanResult plan, TaskStepEntity planner) {
        List<UUID> repositories = worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        List<TaskStepEntity> created = new ArrayList<>();
        UUID previous = planner.getId();
        int sequence = planner.getSequenceNo() + 1;
        for (PlanResult.ImplementationStep item : plan.getImplementationSteps()) {
            TaskStepEntity step = step(task, sequence++, item.getTitle(), developerInstruction(plan, item), "DEVELOPER",
                    item.getRequiredCapabilities(), "完成 " + item.getTitle() + " 并通过相关自检");
            steps.insert(step);
            dependencies.insertLink(step.getId(), previous);
            insertScopes(step.getId(), repositories, "WRITE");
            created.add(step);
            previous = step.getId();
        }
        TaskStepEntity tester = step(task, sequence++, "Verify", plan.getTestPlan(), "TESTER", List.of(),
                "执行计划测试并记录真实结果");
        steps.insert(tester);
        dependencies.insertLink(tester.getId(), previous);
        insertScopes(tester.getId(), repositories, "READ");
        created.add(tester);
        TaskStepEntity reviewer = step(task, sequence, "Review", "审查本次改动是否符合需求、质量与安全要求", "REVIEWER",
                List.of(), "完成独立代码审查");
        steps.insert(reviewer);
        dependencies.insertLink(reviewer.getId(), tester.getId());
        insertScopes(reviewer.getId(), repositories, "READ");
        created.add(reviewer);
        created.forEach(step -> registerStepEvent(task, step));
    }

    private TaskStepEntity step(TaskEntity task, int sequence, String title, String instruction, String role,
                                List<String> requiredCapabilities, String acceptance) {
        TaskStepEntity step = new TaskStepEntity();
        step.setId(UuidV7.next());
        step.setTaskId(task.getId());
        step.setSequenceNo(sequence);
        step.setTitle(title);
        step.setInstruction(instruction);
        step.setRole(role);
        step.setRequiredCapabilities(requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities));
        step.setAssignedAgentId(resolveAgent(task, role, step.getRequiredCapabilities()));
        step.setAcceptanceCriteria(acceptance);
        step.setStatus("PENDING");
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        step.setUpdatedAt(step.getCreatedAt());
        return step;
    }

    private UUID resolveAgent(TaskEntity task, String role, List<String> requiredCapabilities) {
        ProjectEntity project = projects.selectById(task.getProjectId());
        if (project == null || project.getTeamId() == null) {
            return null;
        }
        List<AgentEntity> candidates = agents.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, project.getTeamId()).eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .and(visibility -> visibility.eq(AgentEntity::getVisibility, "TEAM")
                        .or(owner -> owner.eq(AgentEntity::getVisibility, "PRIVATE")
                                .eq(AgentEntity::getCreatedBy, task.getCreatedBy()))));
        // 选用决策交由 AgentMatchDecider：把 role + 候选的 name/description + 步骤能力要求丢给决策
        // Agent（LLM）判断；查不到候选/决策失败时返回 null，执行期由 AgentRegistry 内置兜底或跳步，
        // 不使任务失败。
        return agentMatchDecider.decide(role, candidates, task.getCreatedBy(), requiredCapabilities)
                .map(AgentEntity::getId)
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

    private Map<String, Object> planSummary(PlanResult plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskUnderstanding", plan.getTaskUnderstanding());
        result.put("objectives", plan.getObjectives());
        result.put("steps", plan.getImplementationSteps().stream().map(item -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("title", item.getTitle());
            step.put("files", item.getFiles());
            step.put("description", item.getDescription());
            step.put("requiredCapabilities", item.getRequiredCapabilities());
            return step;
        }).toList());
        result.put("testPlan", plan.getTestPlan());
        result.put("risks", plan.getRisks());
        return result;
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
