package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.WorkBranchResponse;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkBranchServiceTest {
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final TestRunMapper testRuns = mock(TestRunMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private WorkBranchService service;

    @BeforeEach
    void setUp() {
        service = new WorkBranchService(worktrees, tasks, diffs, mergeRequests, testRuns, groups,
                projectRepositories, access);
    }

    @Test
    void returnsTaskScopedDiffDirectlyAndOnlyCurrentHeadVerification() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID(), historicalTaskId = UUID.randomUUID(), latestTaskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), diffId = UUID.randomUUID(), mrId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "feat/login", "head-current", now);
        TaskEntity historicalTask = task(historicalTaskId, projectId, workspaceId, groupId, "T-1", now.minusHours(2));
        TaskEntity latestTask = task(latestTaskId, projectId, workspaceId, groupId, "T-2", now.minusMinutes(1));
        DiffEntity historicalDiff = diff(diffId, projectId, historicalTaskId, workspaceId, repositoryId, "feat/login", now.minusHours(1));
        MergeRequestEntity mr = mr(mrId, repositoryId, "feat/login", now.minusMinutes(2));
        TestRunEntity staleTest = test(projectId, repositoryId, "old-head", "FAILED", now.minusMinutes(5));
        TestRunEntity currentTest = test(projectId, repositoryId, "head-current", "PASSED", now.minusMinutes(3));
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId); group.setProjectId(projectId); group.setName("登录功能"); group.setGroupType("REQUIREMENT");

        when(worktrees.selectByProject(projectId, null)).thenReturn(List.of(worktree));
        when(tasks.selectList(any())).thenReturn(List.of(historicalTask, latestTask));
        when(diffs.selectList(any())).thenReturn(List.of(historicalDiff));
        when(mergeRequests.selectList(any())).thenReturn(List.of(mr));
        when(testRuns.selectList(any())).thenReturn(List.of(staleTest, currentTest));
        when(groups.selectBatchIds(any())).thenReturn(List.of(group));

        ApiPageResponse<WorkBranchResponse> result = service.list(projectId, actor, null, null, null, 20, "req-1");

        assertEquals(1, result.data().size());
        WorkBranchResponse branch = result.data().getFirst();
        assertEquals(repositoryId.toString(), branch.getProjectRepositoryId());
        assertEquals("feat/login", branch.getName());
        assertEquals(latestTaskId.toString(), branch.getLatestTask().getId());
        assertEquals(diffId.toString(), branch.getLatestDiff().getId());
        assertEquals(historicalTaskId.toString(), branch.getLatestDiff().getTaskId());
        assertEquals(mrId.toString(), branch.getOpenMergeRequest().getId());
        assertEquals("TEST_RUN", branch.getLastVerification().getKind());
        assertEquals("head-current", branch.getLastVerification().getCommitSha());
        assertEquals("PASSED", branch.getLastVerification().getStatus());
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void filtersByAnyAssociatedTaskRequirementGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID workspaceOne = UUID.randomUUID(), workspaceTwo = UUID.randomUUID();
        UUID wantedGroup = UUID.randomUUID(), otherGroup = UUID.randomUUID();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(wantedGroup); group.setProjectId(projectId); group.setGroupType("REQUIREMENT");

        when(groups.selectById(wantedGroup)).thenReturn(group);
        when(worktrees.selectByProject(projectId, null)).thenReturn(List.of(
                worktree(workspaceOne, repositoryId, "feat/one", "head-one", LocalDateTime.now()),
                worktree(workspaceTwo, repositoryId, "feat/two", "head-two", LocalDateTime.now())));
        when(tasks.selectList(any())).thenReturn(List.of(
                task(UUID.randomUUID(), projectId, workspaceOne, wantedGroup, "T-1", LocalDateTime.now()),
                task(UUID.randomUUID(), projectId, workspaceTwo, otherGroup, "T-2", LocalDateTime.now())));
        when(diffs.selectList(any())).thenReturn(List.of());
        when(mergeRequests.selectList(any())).thenReturn(List.of());
        when(testRuns.selectList(any())).thenReturn(List.of());
        when(groups.selectBatchIds(any())).thenReturn(List.of(group));

        ApiPageResponse<WorkBranchResponse> result = service.list(projectId, actor, null, wantedGroup, null, 20, "req-1");

        assertEquals(1, result.data().size());
        assertEquals("feat/one", result.data().getFirst().getName());
    }

    @Test
    void doesNotReturnStaleVerificationForCurrentBranchHead() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(worktrees.selectByProject(projectId, null)).thenReturn(List.of(
                worktree(workspaceId, repositoryId, "feat/x", "new-head", LocalDateTime.now())));
        when(tasks.selectList(any())).thenReturn(List.of());
        when(diffs.selectList(any())).thenReturn(List.of());
        when(mergeRequests.selectList(any())).thenReturn(List.of());
        when(testRuns.selectList(any())).thenReturn(List.of(test(projectId, repositoryId, "old-head", "PASSED", LocalDateTime.now())));

        WorkBranchResponse branch = service.list(projectId, actor, null, null, null, 20, "req-1").data().getFirst();

        assertNull(branch.getLastVerification());
    }

    @Test
    void rejectsExpiredCursor() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(worktrees.selectByProject(projectId, null)).thenReturn(List.of(
                worktree(UUID.randomUUID(), repositoryId, "feat/x", "head", LocalDateTime.now())));
        when(tasks.selectList(any())).thenReturn(List.of());
        when(diffs.selectList(any())).thenReturn(List.of());
        when(mergeRequests.selectList(any())).thenReturn(List.of());
        when(testRuns.selectList(any())).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class,
                () -> service.list(projectId, actor, null, null, "not-a-cursor", 20, "req-1"));

        assertEquals("INVALID_CURSOR", error.code());
    }

    @Test
    void rejectsRepositoryOutsideProject() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(UUID.randomUUID());
        when(projectRepositories.selectById(repositoryId)).thenReturn(repository);

        ApiException error = assertThrows(ApiException.class,
                () -> service.list(projectId, actor, repositoryId, null, null, 20, "req-1"));

        assertEquals("REPOSITORY_NOT_FOUND", error.code());
        verifyNoInteractions(worktrees);
    }

    private WorkspaceRepositoryEntity worktree(UUID workspaceId, UUID repositoryId, String branch, String head,
                                                LocalDateTime updatedAt) {
        WorkspaceRepositoryEntity value = new WorkspaceRepositoryEntity();
        value.setWorkspaceId(workspaceId); value.setProjectRepositoryId(repositoryId); value.setSourceBranch(branch);
        value.setHeadCommit(head); value.setUpdatedAt(updatedAt); return value;
    }

    private TaskEntity task(UUID taskId, UUID projectId, UUID workspaceId, UUID groupId, String code,
                            LocalDateTime updatedAt) {
        TaskEntity value = new TaskEntity();
        value.setId(taskId); value.setProjectId(projectId); value.setWorkspaceId(workspaceId);
        value.setRequirementGroupId(groupId); value.setDisplayCode(code); value.setTitle(code + " title");
        value.setUpdatedAt(updatedAt); return value;
    }

    private DiffEntity diff(UUID diffId, UUID projectId, UUID taskId, UUID workspaceId, UUID repositoryId,
                            String branch, LocalDateTime createdAt) {
        DiffEntity value = new DiffEntity();
        value.setId(diffId); value.setProjectId(projectId); value.setTaskId(taskId); value.setWorkspaceId(workspaceId);
        value.setProjectRepositoryId(repositoryId); value.setSourceBranch(branch); value.setStatus("PENDING_REVIEW");
        value.setCreatedAt(createdAt); return value;
    }

    private MergeRequestEntity mr(UUID mrId, UUID repositoryId, String branch, LocalDateTime updatedAt) {
        MergeRequestEntity value = new MergeRequestEntity();
        value.setId(mrId); value.setProjectRepositoryId(repositoryId); value.setSourceBranch(branch);
        value.setStatus("OPEN"); value.setProviderNumber(42L); value.setProviderUpdatedAt(updatedAt); return value;
    }

    private TestRunEntity test(UUID projectId, UUID repositoryId, String commitSha, String status,
                               LocalDateTime updatedAt) {
        TestRunEntity value = new TestRunEntity();
        value.setId(UUID.randomUUID()); value.setProjectId(projectId); value.setProjectRepositoryId(repositoryId);
        value.setExecutionSourceRef(commitSha); value.setStatus(status); value.setUpdatedAt(updatedAt); return value;
    }
}
