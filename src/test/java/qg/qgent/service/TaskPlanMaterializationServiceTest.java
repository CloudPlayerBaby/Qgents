package qg.qgent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.api.ApiException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import qg.qgent.orchestration.result.PlanResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Plan 物化服务测试：同一事务内冻结多条开发步骤并在持久化时确定 Agent。
 */
class TaskPlanMaterializationServiceTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void materializesAtomicDeveloperStepsAndBindsCapabilityMatchedAgent() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
        TaskStepRepositoryMapper scopes = mock(TaskStepRepositoryMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        TaskExecutionArtifactService artifacts = mock(TaskExecutionArtifactService.class);
        EventService events = mock(EventService.class);
        TaskPlanMaterializationService service = new TaskPlanMaterializationService(tasks, steps, dependencies, scopes,
                worktrees, projects, agents, artifacts, events);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        ProjectEntity project = new ProjectEntity();
        project.setTeamId(UUID.randomUUID());
        AgentEntity javaAgent = new AgentEntity();
        javaAgent.setId(UUID.randomUUID());
        javaAgent.setName("Java");
        javaAgent.setVisibility("TEAM");
        javaAgent.setCapabilities(List.of("java", "spring-boot"));
        WorkspaceRepositoryEntity repository = new WorkspaceRepositoryEntity();
        repository.setProjectRepositoryId(UUID.randomUUID());
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of(javaAgent));
        when(worktrees.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of(repository));
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
        TaskPlanMaterializationService service = new TaskPlanMaterializationService(tasks, steps,
                mock(TaskStepDependencyMapper.class), mock(TaskStepRepositoryMapper.class), mock(WorkspaceRepositoryMapper.class),
                mock(ProjectMapper.class), mock(AgentMapper.class), mock(TaskExecutionArtifactService.class), mock(EventService.class));

        assertThat(service.materialize(task, plan())).isSameAs(existing);
        verify(steps, never()).insert(any(TaskStepEntity.class));
    }

    @Test
    void specializedDeveloperStepDoesNotFallBackToBuiltinWhenNoAgentMatchesAllCapabilities() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        TaskEntity task = task();
        TaskStepEntity planner = planner(task);
        ProjectEntity project = new ProjectEntity();
        project.setTeamId(UUID.randomUUID());
        AgentEntity partial = new AgentEntity();
        partial.setCapabilities(List.of("java"));
        partial.setVisibility("TEAM");
        when(tasks.selectByIdForUpdate(task.getId())).thenReturn(task);
        when(steps.selectByTaskForUpdate(task.getId())).thenReturn(List.of(planner));
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of(partial));
        TaskPlanMaterializationService service = new TaskPlanMaterializationService(tasks, steps,
                mock(TaskStepDependencyMapper.class), mock(TaskStepRepositoryMapper.class), mock(WorkspaceRepositoryMapper.class),
                projects, agents, mock(TaskExecutionArtifactService.class), mock(EventService.class));

        assertThatThrownBy(() -> service.materialize(task, plan())).isInstanceOf(ApiException.class)
                .hasMessageContaining("未找到满足步骤能力要求");
        verify(steps, never()).insert(any(TaskStepEntity.class));
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
