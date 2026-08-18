package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkBranchDevelopmentGuardTest {
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final WorkBranchDevelopmentGuard guard = new WorkBranchDevelopmentGuard(worktrees, mergeRequests);

    @Test
    void openMrBlocksContinuationWithStructuredDetails() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        MergeRequestEntity mr = mergeRequest(repositoryId, "OPEN");
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree(workspaceId, repositoryId)));
        when(mergeRequests.selectOne(any())).thenReturn(mr);

        ApiException error = assertThrows(ApiException.class,
                () -> guard.requireContinuationAllowed(UUID.randomUUID(), workspaceId));

        assertEquals("WORKSPACE_CONTINUATION_BLOCKED_BY_OPEN_MR", error.code());
        assertEquals(1, error.details().size());
    }

    @Test
    void closedUnmergedMrAlsoBlocksWorkerWrites() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree(workspaceId, repositoryId)));
        when(mergeRequests.selectOne(any())).thenReturn(mergeRequest(repositoryId, "CLOSED"));

        ApiException error = assertThrows(ApiException.class,
                () -> guard.requireWorkerWriteAllowed(UUID.randomUUID(), workspaceId));

        assertEquals("WORKSPACE_WRITE_BLOCKED_BY_OPEN_MR", error.code());
    }

    @Test
    void mergedMrDoesNotBlockContinuation() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree(workspaceId, repositoryId)));
        when(mergeRequests.selectOne(any())).thenReturn(null);

        guard.requireContinuationAllowed(UUID.randomUUID(), workspaceId);
    }

    private WorkspaceRepositoryEntity worktree(UUID workspaceId, UUID repositoryId) {
        WorkspaceRepositoryEntity value = new WorkspaceRepositoryEntity();
        value.setWorkspaceId(workspaceId);
        value.setProjectRepositoryId(repositoryId);
        value.setSourceBranch("feat/task");
        return value;
    }

    private MergeRequestEntity mergeRequest(UUID repositoryId, String status) {
        MergeRequestEntity value = new MergeRequestEntity();
        value.setId(UUID.randomUUID());
        value.setProjectRepositoryId(repositoryId);
        value.setProviderNumber(42L);
        value.setSourceBranch("feat/task");
        value.setStatus(status);
        return value;
    }
}
