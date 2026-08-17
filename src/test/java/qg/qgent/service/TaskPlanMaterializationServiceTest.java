package qg.qgent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.entity.AgentEntity;
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
import qg.qgent.mapper.RepositoryBranchConfigMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Plan 物化服务测试：同一事务内冻结多条开发步骤并在持久化时经调度 Agent 确定 Agent。
 * Agent 选用统一委托给 {@link AgentDispatcher}（候选池查询 + 决策收敛于此），本服务不持有选择逻辑。
 */
class TaskPlanMaterializationServiceTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private TaskPlanMaterializationService service(TaskMapper tasks, TaskStepMapper steps,
                                                   TaskStepDependencyMapper dependencies, TaskStepRepositoryMapper scopes,
                                                   WorkspaceRepositoryMapper worktrees,
                                                   TaskExecutionArtifactService artifacts,
                                                   EventService events, AgentDispatcher dispatcher) {
        return new TaskPlanMaterializationService(tasks, steps, dependencies, scopes, worktrees, artifacts, events,
                dispatcher, new DeliveryModeDecider(), mock(RepositoryBranchConfigMapper.class));
    }

    @Test
    void materializesAtomicDeveloperStepsAndBindsDispatchedAgent() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
        TaskStepRepositoryMapper scopes = mock(TaskStepRepositoryMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        TaskExecutionArtifactService artifacts = mock(TaskExecutionArtifactService.class);
        EventService events = mock(EventService.class);
        AgentDispatcher dispatcher = mock(AgentDispatcher.class);
        TaskPlanMaterializationService service = service(tasks, steps, dependencies, scopes, worktrees, artifacts,
                events, dispatcher);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        AgentEntity javaAgent = new AgentEntity();
        javaAgent.setId(UUID.randomUUID());
        javaAgent.setName("Java");
        javaAgent.setDescription("Java 后端实现");
        javaAgent.setVisibility("TEAM");
        WorkspaceRepositoryEntity repository = new WorkspaceRepositoryEntity();
        repository.setProjectRepositoryId(UUID.randomUUID());
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository));
        when(dispatcher.dispatch(any(), any(), any())).thenReturn(Optional.of(javaAgent));
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan());

        ArgumentCaptor<TaskStepEntity> inserted = ArgumentCaptor.forClass(TaskStepEntity.class);
        verify(steps, times(4)).insert(inserted.capture());
        List<TaskStepEntity> generated = inserted.getAllValues();
        assertThat(generated).extracting(TaskStepEntity::getRole)
                .containsExactly("DEVELOPER", "DEVELOPER", "TESTER", "REVIEWER");
        assertThat(generated.get(0).getRequiredCapabilities()).containsExactly("java", "spring-boot");
        assertThat(generated.get(0).getAssignedAgentId()).isEqualTo(javaAgent.getId());
        assertThat(generated.get(1).getAssignedAgentId()).isEqualTo(javaAgent.getId());
        // 调度 Agent 收到步骤角色 + 步骤能力要求
        verify(dispatcher, atLeast(1)).dispatch(eq(task), eq("DEVELOPER"), eq(List.of("java", "spring-boot")));
        verify(dependencies, times(4)).insertLink(any(), any());
        verify(artifacts).createPlan(eq(task), any());
        assertThat(task.getPlanMaterializedAt()).isNotNull();
    }

    @Test
    void materializedTaskIsIdempotent() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        TaskEntity task = task();
        task.setPlanMaterializedAt(java.time.LocalDateTime.now());
        List<TaskStepEntity> existing = List.of(planner(task));
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(existing);
        AgentDispatcher dispatcher = mock(AgentDispatcher.class);
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), mock(WorkspaceRepositoryMapper.class),
                mock(TaskExecutionArtifactService.class), mock(EventService.class), dispatcher);

        assertThat(service.materialize(task, plan())).isSameAs(existing);
        verify(steps, never()).insert(any(TaskStepEntity.class));
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    void missingAgentYieldsNullAssignedIdWithoutThrowing() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        AgentDispatcher dispatcher = mock(AgentDispatcher.class);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(dispatcher.dispatch(any(), any(), any())).thenReturn(Optional.empty());
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), mock(WorkspaceRepositoryMapper.class),
                mock(TaskExecutionArtifactService.class), mock(EventService.class), dispatcher);
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan());

        ArgumentCaptor<TaskStepEntity> inserted = ArgumentCaptor.forClass(TaskStepEntity.class);
        verify(steps, atLeast(1)).insert(inserted.capture());
        // 调度 Agent 选不到 → 步骤不绑 Agent，执行期由 AgentRegistry 内置兜底，不抛错。
        assertThat(inserted.getAllValues()).extracting(TaskStepEntity::getAssignedAgentId)
                .allMatch(java.util.Objects::isNull);
    }

    @Test
    void explicitDeliveryModeWinsOverPlannerAndRule() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        TaskEntity task = task();
        task.setDeliveryMode(DeliveryMode.MR_FIRST);
        TaskStepEntity planner = planner(task);
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository()));
        PlanResult plan = plan();
        plan.setDeliveryMode(DeliveryMode.DIFF_FIRST);
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), worktrees,
                mock(TaskExecutionArtifactService.class), mock(EventService.class), mock(AgentDispatcher.class));
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan);

        ArgumentCaptor<TaskEntity> updated = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).updateById(updated.capture());
        assertThat(updated.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.MR_FIRST);
    }

    @Test
    void plannerDeliveryModeUsedWhenTaskUnset() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository()));
        PlanResult plan = plan();
        plan.setDeliveryMode(DeliveryMode.MR_FIRST);
        plan.setScaleReason("跨模块新功能，值得走人工审查");
        TaskExecutionArtifactService artifacts = mock(TaskExecutionArtifactService.class);
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), worktrees, artifacts,
                mock(EventService.class), mock(AgentDispatcher.class));
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan);

        ArgumentCaptor<TaskEntity> updated = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).updateById(updated.capture());
        assertThat(updated.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.MR_FIRST);
        assertThat(updated.getValue().getDeliveryReason()).isEqualTo("跨模块新功能，值得走人工审查");
        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(artifacts).createPlan(any(), summary.capture());
        assertThat(summary.getValue()).containsEntry("deliveryMode", DeliveryMode.MR_FIRST)
                .containsEntry("scaleReason", "跨模块新功能，值得走人工审查");
    }

    @Test
    void ruleFallbackPicksMrFirstForMultipleRepositories() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository(), repository()));
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), worktrees,
                mock(TaskExecutionArtifactService.class), mock(EventService.class), mock(AgentDispatcher.class));
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan());

        ArgumentCaptor<TaskEntity> updated = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).updateById(updated.capture());
        assertThat(updated.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.MR_FIRST);
        assertThat(updated.getValue().getDeliveryReason()).contains("仓库").contains("MR_FIRST");
    }

    @Test
    void ruleFallbackDefaultsDiffFirstForSingleSmallTask() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository()));
        TaskPlanMaterializationService service = service(tasks, steps, mock(TaskStepDependencyMapper.class),
                mock(TaskStepRepositoryMapper.class), worktrees,
                mock(TaskExecutionArtifactService.class), mock(EventService.class), mock(AgentDispatcher.class));
        TransactionSynchronizationManager.initSynchronization();

        service.materialize(task, plan());

        ArgumentCaptor<TaskEntity> updated = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).updateById(updated.capture());
        assertThat(updated.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.DIFF_FIRST);
        assertThat(updated.getValue().getDeliveryReason()).contains("DIFF_FIRST");
    }

    private WorkspaceRepositoryEntity repository() {
        WorkspaceRepositoryEntity repository = new WorkspaceRepositoryEntity();
        repository.setProjectRepositoryId(UUID.randomUUID());
        repository.setBaseCommit("develop");
        return repository;
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(UUID.randomUUID());
        task.setRequirementGroupId(UUID.randomUUID());
        task.setWorkspaceId(UUID.randomUUID());
        task.setCreatedBy(UUID.randomUUID());
        return task;
    }

    private TaskStepEntity planner(TaskEntity task) {
        TaskStepEntity planner = new TaskStepEntity();
        planner.setId(UUID.randomUUID());
        planner.setTaskId(task.getId());
        planner.setRole("PLANNER");
        planner.setSequenceNo(1);
        return planner;
    }

    private PlanResult plan() {
        PlanResult plan = new PlanResult();
        plan.setTaskUnderstanding("understanding");
        plan.setObjectives(List.of("goal"));
        plan.setTestPlan("run tests");
        PlanResult.ImplementationStep java = new PlanResult.ImplementationStep();
        java.setTitle("Java change");
        java.setFiles(List.of("src/App.java"));
        java.setRequiredCapabilities(List.of("java", "spring-boot"));
        PlanResult.ImplementationStep generic = new PlanResult.ImplementationStep();
        generic.setTitle("Generic change");
        generic.setFiles(List.of("README.md"));
        plan.setImplementationSteps(List.of(java, generic));
        return plan;
    }
}