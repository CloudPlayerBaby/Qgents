package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Verifies Task delivery versioning after a rejected result is revised. */
class DeliverableServiceTaskDeliveryTest {
    @Test
    void rejectedDeliveryCreatesNextVersionForRevision() {
        DeliverableMapper deliverables = mock(DeliverableMapper.class);
        TaskDeliveryMapper deliveries = mock(TaskDeliveryMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TaskStepMapper steps = mock(TaskStepMapper.class);
        TaskRunMapper runs = mock(TaskRunMapper.class);
        TaskRepositoryMapper repositories = mock(TaskRepositoryMapper.class);
        DeliverableService service = new DeliverableService(deliverables, mock(DiffMapper.class),
                mock(DiffFileMapper.class), mock(DiffCommentMapper.class), deliveries, tasks, steps, runs,
                repositories, mock(ProjectAccessService.class), mock(EventService.class));
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), runId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setRequirementGroupId(groupId); task.setCreatedBy(creator);
        TaskStepEntity step = new TaskStepEntity(); step.setId(stepId); step.setTaskId(taskId);
        TaskRunEntity run = new TaskRunEntity(); run.setId(runId); run.setProjectId(projectId);
        run.setTaskId(taskId); run.setTaskStepId(stepId);
        TaskRepositoryEntity repository = new TaskRepositoryEntity(); repository.setTaskId(taskId);
        repository.setProjectRepositoryId(repositoryId);
        TaskDeliveryEntity rejected = new TaskDeliveryEntity(); rejected.setId(UUID.randomUUID());
        rejected.setTaskId(taskId); rejected.setProjectId(projectId); rejected.setVersion(1); rejected.setStatus("REJECTED");
        when(tasks.selectByIdForUpdate(taskId)).thenReturn(task); when(steps.selectById(stepId)).thenReturn(step);
        when(runs.selectById(runId)).thenReturn(run); when(repositories.selectByTask(taskId)).thenReturn(List.of(repository));
        when(deliveries.selectOne(any())).thenReturn(rejected); when(deliveries.maxVersion(taskId)).thenReturn(1);

        service.createFromTaskExecution(projectId, taskId, stepId, runId, groupId, repositoryId,
                "feat/login", "abc123", Map.of("tests", "passed"), creator);

        verify(deliveries).insert(argThat((TaskDeliveryEntity value) -> value.getVersion() == 2
                && "PENDING_REVIEW".equals(value.getStatus())));
        verify(deliverables).insert(argThat((DeliverableEntity value) -> taskId.equals(value.getTaskId())
                && repositoryId.equals(value.getProjectRepositoryId())));
    }
}
