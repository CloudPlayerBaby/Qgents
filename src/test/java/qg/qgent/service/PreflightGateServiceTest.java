package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.PreflightCqReviewEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.PreflightCqReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreflightGateServiceTest {
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final PreflightCqReviewMapper cqReviews = mock(PreflightCqReviewMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final PreflightGateService service = new PreflightGateService(dryRuns, cqReviews, tasks, worktrees,
            repositories, mock(ProjectAccessService.class), mock(GitStoreSyncService.class), mock(EventService.class));

    @Test
    void blocksMrWhenCurrentDryRunHasNoIndependentCqApproval() {
        Context context = context();
        when(dryRuns.selectOne(any())).thenReturn(context.dryRun());
        when(cqReviews.selectOne(any())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.requireReady(context.task(),
                context.worktree(), context.repositoryId(), "main", "target-commit"));

        assertEquals("MR_PREFLIGHT_NOT_PASSED", error.code());
        assertEquals(409, error.status().value());
        assertFalse(error.details().isEmpty());
    }

    @Test
    void allowsMrOnlyWhenDryRunAndCqBindTheSameSourceAndTargetCommits() {
        Context context = context();
        PreflightCqReviewEntity review = new PreflightCqReviewEntity();
        review.setDecision("APPROVED");
        review.setSourceCommit("source-commit");
        review.setTargetCommit("target-commit");
        review.setReviewerUserId(UUID.randomUUID());
        when(dryRuns.selectOne(any())).thenReturn(context.dryRun());
        when(cqReviews.selectOne(any())).thenReturn(review);

        assertDoesNotThrow(() -> service.requireReady(context.task(), context.worktree(), context.repositoryId(),
                "main", "target-commit"));
    }

    @Test
    void returnsOnlyTheVerifiedDryRunAndIndependentCqEvidence() {
        Context context = context();
        context.task().setCreatedBy(UUID.randomUUID());
        PreflightCqReviewEntity review = new PreflightCqReviewEntity();
        review.setId(UUID.randomUUID());
        review.setDecision("APPROVED");
        review.setSourceCommit("source-commit");
        review.setTargetCommit("target-commit");
        review.setReviewerUserId(UUID.randomUUID());
        when(dryRuns.selectOne(any())).thenReturn(context.dryRun());
        when(cqReviews.selectOne(any())).thenReturn(review);

        PreflightGateService.PreflightEvidence evidence = service.requireEvidence(context.task(), context.worktree(),
                context.repositoryId(), "main", "target-commit");

        assertSame(context.dryRun(), evidence.dryRun());
        assertSame(review, evidence.cqReview());
    }

    @Test
    void completedMrFirstTaskCanRevalidatePreflightToRestoreAnExternallyClosedMr() {
        Context context = context();
        context.task().setStatus("SUCCEEDED");
        PreflightCqReviewEntity review = new PreflightCqReviewEntity();
        review.setDecision("APPROVED");
        review.setSourceCommit("source-commit");
        review.setTargetCommit("target-commit");
        review.setReviewerUserId(UUID.randomUUID());
        when(dryRuns.selectOne(any())).thenReturn(context.dryRun());
        when(cqReviews.selectOne(any())).thenReturn(review);

        assertDoesNotThrow(() -> service.requireReady(context.task(), context.worktree(), context.repositoryId(),
                "main", "target-commit"));
    }

    @Test
    void taskCreatorLegacyCqCannotSatisfyPreflight() {
        Context context = context();
        UUID taskCreator = UUID.randomUUID();
        context.task().setCreatedBy(taskCreator);
        PreflightCqReviewEntity review = new PreflightCqReviewEntity();
        review.setDecision("APPROVED");
        review.setSourceCommit("source-commit");
        review.setTargetCommit("target-commit");
        review.setReviewerUserId(taskCreator);
        when(dryRuns.selectOne(any())).thenReturn(context.dryRun());
        when(cqReviews.selectOne(any())).thenReturn(review);

        ApiException error = assertThrows(ApiException.class, () -> service.requireReady(context.task(),
                context.worktree(), context.repositoryId(), "main", "target-commit"));

        assertEquals("MR_PREFLIGHT_NOT_PASSED", error.code());
    }

    @Test
    void taskCreatorCannotApproveOwnPreflight() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(dryRunId);
        dryRun.setProjectId(projectId);
        dryRun.setTaskId(UUID.randomUUID());
        dryRun.setStatus("PASSED");
        when(dryRuns.selectById(dryRunId)).thenReturn(dryRun);
        TaskEntity task = new TaskEntity();
        task.setId(dryRun.getTaskId());
        task.setProjectId(projectId);
        task.setCreatedBy(actor);
        when(tasks.selectById(dryRun.getTaskId())).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(projectId, dryRunId, actor, "looks good"));

        assertEquals("PREFLIGHT_CQ_AUTHOR_FORBIDDEN", error.code());
    }

    @Test
    void independentReviewerCannotApproveBeforeTaskReachesPreflightStage() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(dryRunId);
        dryRun.setProjectId(projectId);
        dryRun.setTaskId(UUID.randomUUID());
        dryRun.setStatus("PASSED");
        when(dryRuns.selectById(dryRunId)).thenReturn(dryRun);

        TaskEntity task = new TaskEntity();
        task.setId(dryRun.getTaskId());
        task.setProjectId(projectId);
        task.setCreatedBy(UUID.randomUUID());
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("RUNNING");
        when(tasks.selectById(dryRun.getTaskId())).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(projectId, dryRunId, actor, "looks good"));

        assertEquals("PREFLIGHT_TASK_NOT_READY", error.code());
        verify(cqReviews, never()).insert(any(PreflightCqReviewEntity.class));
    }

    private Context context() {
        UUID projectId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(projectId);
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("WAITING_PREFLIGHT");
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setHeadCommit("source-commit");
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(UUID.randomUUID());
        dryRun.setStatus("PASSED");
        dryRun.setHeadCommit("source-commit");
        dryRun.setResolvedTargetCommit("target-commit");
        return new Context(repositoryId, task, worktree, dryRun);
    }

    private record Context(UUID repositoryId, TaskEntity task, WorkspaceRepositoryEntity worktree, DryRunEntity dryRun) {
    }
}
