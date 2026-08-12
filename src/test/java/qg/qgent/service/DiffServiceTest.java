package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Review-state tests for uncommitted Task Diff snapshots. */
class DiffServiceTest {
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final DiffService service = new DiffService(diffs, mock(DiffFileMapper.class),
            mock(DiffCommentMapper.class), tasks, workspaces, access);

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
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(diff.getWorkspaceId()); workspace.setProjectId(projectId);
        when(diffs.selectById(diff.getId())).thenReturn(diff); when(tasks.selectById(taskId)).thenReturn(task);
        when(workspaces.selectByIdForUpdate(diff.getWorkspaceId())).thenReturn(workspace);

        service.decide(projectId, diff.getId(), actor, true, null);

        assertEquals("ACCEPTED", diff.getStatus()); assertEquals(actor, diff.getReviewedBy());
        assertNull(diff.getHeadCommit()); verify(workspaces).selectByIdForUpdate(diff.getWorkspaceId());
        verify(diffs).updateById(diff);
    }

    private DiffEntity diff(UUID projectId, UUID taskId) {
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID()); diff.setProjectId(projectId);
        diff.setTaskId(taskId); diff.setWorkspaceId(UUID.randomUUID()); diff.setProjectRepositoryId(UUID.randomUUID());
        diff.setStatus("PENDING_REVIEW"); diff.setWorkingTreeHash("tree-hash"); return diff;
    }
}
