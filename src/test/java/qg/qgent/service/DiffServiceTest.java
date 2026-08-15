package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DiffListItemResponse;
import qg.qgent.dto.DiffResponse;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Review-state tests for uncommitted Task Diff snapshots. */
class DiffServiceTest {
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final DiffDeliveryService delivery = mock(DiffDeliveryService.class);
    private final DiffService service = new DiffService(diffs, mock(DiffFileMapper.class),
            mock(DiffCommentMapper.class), tasks, workspaces, access, events,
            mock(NotificationService.class), delivery);

    @Test
    void rejectRequiresReasonAndDoesNotCreateACommit() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId); TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(diff.getWorkspaceId()); task.setCreatedBy(actor);
        when(diffs.selectById(diff.getId())).thenReturn(diff); when(tasks.selectById(taskId)).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.decide(projectId, diff.getId(), actor, false, " "));

        assertEquals("DIFF_REJECT_REASON_REQUIRED", error.code());
        assertNull(diff.getHeadCommit()); verify(diffs, never()).updateById(diff);
    }

    @Test
    void acceptRecordsApprovalForTheExactSnapshot() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId); TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(diff.getWorkspaceId()); task.setCreatedBy(actor);
        when(diffs.selectById(diff.getId())).thenReturn(diff); when(tasks.selectById(taskId)).thenReturn(task);
        diff.setHeadCommit("base-head");
        DiffEntity committed = diff(projectId, taskId);
        committed.setId(diff.getId()); committed.setStatus("ACCEPTED"); committed.setReviewedBy(actor);
        committed.setHeadCommit("real-commit"); committed.setDeliveryStatus("COMMITTED");
        when(delivery.acceptNonBatch(task, diff, actor)).thenReturn(committed);

        DiffResponse response = service.decide(projectId, diff.getId(), actor, true, null);

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals("real-commit", response.getHeadCommit());
        verify(delivery).acceptNonBatch(task, diff, actor);
    }

    @Test
    void listReturnsProjectDiffsWithRequirementGroupDerivedFromTask() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setRequirementGroupId(groupId);
        when(tasks.selectBatchIds(Set.of(taskId))).thenReturn(List.of(task));

        ApiPageResponse<DiffListItemResponse> page = service.list(projectId, taskId, actor, null, 20, "req");

        assertEquals(1, page.data().size());
        assertEquals(diff.getId().toString(), page.data().getFirst().getId());
        assertEquals(groupId.toString(), page.data().getFirst().getRequirementGroupId());
        assertEquals(diff.getSourceBranch(), page.data().getFirst().getSourceBranch());
        assertFalse(page.page().getHasMore());
    }

    @Test
    void createSeamPersistsDiffAndPublishesDiffCreated() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), repoId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setRequirementGroupId(groupId);
        when(tasks.selectById(taskId)).thenReturn(task);

        DiffResponse result = service.create(projectId, taskId, runId, stepId, repoId, workspaceId,
                "base", "feat/task-x", "tree-hash", Map.of("files", 2));

        assertEquals(runId.toString(), result.getTaskRunId());
        assertEquals(stepId.toString(), result.getTaskStepId());
        assertEquals(groupId.toString(), result.getRequirementGroupId());
        assertEquals("PENDING_REVIEW", result.getStatus());
        verify(diffs).insert(any(DiffEntity.class));
        verify(events).publish(eq(projectId), any(), eq("diff.created"), any(), any(Map.class));
    }

    private DiffEntity diff(UUID projectId, UUID taskId) {
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID()); diff.setProjectId(projectId);
        diff.setTaskId(taskId); diff.setWorkspaceId(UUID.randomUUID()); diff.setProjectRepositoryId(UUID.randomUUID());
        diff.setStatus("PENDING_REVIEW"); diff.setWorkingTreeHash("tree-hash"); return diff;
    }
}
