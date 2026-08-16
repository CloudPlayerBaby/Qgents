package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.entity.*;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubPullRequestCreateRequest;
import qg.qgent.github.GitHubPullRequestDetails;
import qg.qgent.github.GitHubPullRequestMergeRequest;
import qg.qgent.github.GitHubPullRequestMergeResult;
import qg.qgent.mapper.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MergeRequestServiceTest {

    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final MergeRequestGroupMapper mergeRequestGroupMapper = mock(MergeRequestGroupMapper.class);
    private final QualityCheckResultMapper qualityCheckMapper = mock(QualityCheckResultMapper.class);
    private final MergeRequestReviewMapper reviewMapper = mock(MergeRequestReviewMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
    private final RepositoryBranchConfigMapper branchConfigMapper = mock(RepositoryBranchConfigMapper.class);
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper = mock(RepositoryBranchConfigTestsetMapper.class);
    private final GitHubInstallationMapper githubInstallationMapper = mock(GitHubInstallationMapper.class);
    private final GitHubRepositoryMapper githubRepositoryMapper = mock(GitHubRepositoryMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final GitHubAppClient githubClient = mock(GitHubAppClient.class);
    private final ProjectAccessService projectAccess = mock(ProjectAccessService.class);
    private final EventService eventService = mock(EventService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final qg.qgent.orchestration.worker.SandboxWorkerClient sandboxWorkerClient = mock(qg.qgent.orchestration.worker.SandboxWorkerClient.class);
    private final GitCredentialService gitCredentialService = mock(GitCredentialService.class);
    private final MergeRequestDeliveryOperationMapper deliveryOperations = mock(MergeRequestDeliveryOperationMapper.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final AtomicReference<MergeRequestDeliveryOperationEntity> operation = new AtomicReference<>();

    private MergeRequestService service;

    @BeforeEach
    void setUp() {
        when(mergeRequestMapper.selectByIdForUpdate(any())).thenAnswer(invocation ->
                mergeRequestMapper.selectById(invocation.getArgument(0)));
        when(projectRepositoryMapper.selectByIdForUpdate(any())).thenAnswer(invocation ->
                projectRepositoryMapper.selectById(invocation.getArgument(0)));
        operation.set(null);
        when(deliveryOperations.selectByKeyForUpdate(anyString())).thenAnswer(invocation -> operation.get());
        when(deliveryOperations.selectByIdForUpdate(any())).thenAnswer(invocation -> operation.get());
        when(deliveryOperations.selectById(any())).thenAnswer(invocation -> operation.get());
        when(deliveryOperations.insert((MergeRequestDeliveryOperationEntity)
                any(MergeRequestDeliveryOperationEntity.class))).thenAnswer(invocation -> {
            operation.set(invocation.getArgument(0)); return 1;
        });
        when(deliveryOperations.updateById(any(MergeRequestDeliveryOperationEntity.class))).thenReturn(1);
        when(diffs.selectAcceptedCommittedForMr(any(), any(), any(), any(), anyString()))
                .thenReturn(new DiffEntity());
        when(transactions.execute(any())).thenAnswer(invocation -> {
            inTransaction.set(true);
            try { return ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null); }
            finally { inTransaction.set(false); }
        });
        service = new MergeRequestService(
                mergeRequestMapper, mergeRequestGroupMapper, qualityCheckMapper, reviewMapper,
                taskMapper, workspaceRepositoryMapper, projectRepositoryMapper, branchConfigMapper,
                branchConfigTestsetMapper, projectAccess, eventService, githubInstallationMapper,
                githubRepositoryMapper, projectMapper, githubClient, notificationService,
                sandboxWorkerClient, gitCredentialService, deliveryOperations, transactions, diffs
        );
    }

    @Test
    void createSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requirementGroupId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("Test PR");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        task.setRequirementGroupId(requirementGroupId);
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        when(mergeRequestMapper.selectOne(any())).thenReturn(null);

        when(gitCredentialService.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("mock-grant-id");
        qg.qgent.orchestration.worker.WorkerGitPushResponse mockPushResponse = new qg.qgent.orchestration.worker.WorkerGitPushResponse()
                .setBranch("feature/test")
                .setHeadCommit("sha123")
                .setVerified(true);
        when(sandboxWorkerClient.pushWorkspaceBranch(any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(inTransaction.get());
            return mockPushResponse;
        });

        GitHubPullRequestDetails githubPr = new GitHubPullRequestDetails(
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url"
        );
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any(GitHubPullRequestCreateRequest.class)))
                .thenAnswer(invocation -> { assertFalse(inTransaction.get()); return githubPr; });

        MergeRequestSummaryResponse response = service.create(projectId, userId, request);

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
        assertEquals("feature/test", response.getSourceBranch());
        assertEquals(100L, response.getNumber());
        
        verify(mergeRequestMapper).insert(any(MergeRequestEntity.class));
        verify(mergeRequestGroupMapper).insert(any(MergeRequestGroupEntity.class));
        verify(eventService).publish(any(), any(), eq("merge-request.updated"), any(), any());
    }

    @Test
    void createFailsWhenTaskNotFound() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(UUID.randomUUID());

        when(taskMapper.selectById(request.getTaskId())).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("TASK_NOT_FOUND", ex.code());
    }

    @Test
    void createRequiresAcceptedCommittedDiffForCurrentWorkspaceHead() {
        UUID projectId = UUID.randomUUID(), userId = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId); request.setRepositoryId(repositoryId); request.setTargetBranch("main");
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setCreatedBy(userId); task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feat/x"); worktree.setHeadCommit("head");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);
        when(diffs.selectAcceptedCommittedForMr(taskId, projectId, workspaceId, repositoryId, "head"))
                .thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));

        assertEquals("MR_REVIEWED_DIFF_REQUIRED", error.code());
        verifyNoInteractions(githubClient);
    }

    @Test
    void operationKeySeparatesTasksAndWorkspaces() {
        UUID projectId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setRepositoryId(repositoryId); request.setTargetBranch("main");
        TaskEntity first = new TaskEntity(); first.setId(UUID.randomUUID()); first.setWorkspaceId(UUID.randomUUID());
        TaskEntity second = new TaskEntity(); second.setId(UUID.randomUUID()); second.setWorkspaceId(UUID.randomUUID());
        WorkspaceRepositoryEntity firstTree = new WorkspaceRepositoryEntity();
        firstTree.setSourceBranch("feat/x"); firstTree.setHeadCommit("same-head");
        WorkspaceRepositoryEntity secondTree = new WorkspaceRepositoryEntity();
        secondTree.setSourceBranch("feat/x"); secondTree.setHeadCommit("same-head");

        String firstKey = ReflectionTestUtils.invokeMethod(service, "operationKey", projectId, first, firstTree, request);
        String secondKey = ReflectionTestUtils.invokeMethod(service, "operationKey", projectId, second, secondTree, request);

        assertNotEquals(firstKey, secondKey);
    }

    @Test
    void createFailsWhenWorkerPushVerificationFails() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("Test PR");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        when(mergeRequestMapper.selectOne(any())).thenReturn(null);
        when(gitCredentialService.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("mock-grant-id");
        
        qg.qgent.orchestration.worker.WorkerGitPushResponse mockPushResponse = new qg.qgent.orchestration.worker.WorkerGitPushResponse()
                .setBranch("feature/test")
                .setHeadCommit("sha123")
                .setVerified(false); // verified=false
        when(sandboxWorkerClient.pushWorkspaceBranch(any(), any(), any())).thenReturn(mockPushResponse);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("WORKER_PUSH_VERIFICATION_FAILED", ex.code());
        verify(githubClient, never()).createPullRequest(anyLong(), any(), any(), any());
    }

    @Test
    void mergeSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(repositoryId);
        mr.setStatus("OPEN");
        mr.setProviderNumber(100L);
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of()); // No required checks -> PENDING or PASSED depending on logic
        // If there are no checks, computeGateStatus returns "PASSED".
        
        GitHubPullRequestMergeResult mergeResult = new GitHubPullRequestMergeResult(true, "sha456", "Merged");
        when(githubClient.mergePullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100), any(GitHubPullRequestMergeRequest.class)))
                .thenAnswer(invocation -> {
                    assertFalse(inTransaction.get(), "GitHub 合并不得发生在数据库事务中");
                    return mergeResult;
                });

        MergeRequestSummaryResponse response = service.merge(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("MERGED", response.getStatus());

        verify(mergeRequestMapper, times(2)).updateById(any(MergeRequestEntity.class));
        verify(eventService).publish(any(), any(), eq("merge-request.updated"), any(), any());
    }

    @Test
    void cqApprovalSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setAuthorUserId(authorId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        MergeRequestSummaryResponse response = service.cqApproval(projectId, mergeRequestId, userId, "Looks good");

        assertNotNull(response);
        
        verify(reviewMapper).insert(any(MergeRequestReviewEntity.class));
        verify(qualityCheckMapper).insert(any(QualityCheckResultEntity.class));
        verify(eventService).publish(any(), any(), eq("merge-request.updated"), any(), any());
    }
    
    @Test
    void cqApprovalFailsWhenAuthorApproves() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setAuthorUserId(userId); // Same as reviewer
        mr.setProjectRepositoryId(UUID.randomUUID());
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        ApiException ex = assertThrows(ApiException.class, () -> service.cqApproval(projectId, mergeRequestId, userId, "Looks good"));
        assertEquals("CQ_REVIEWER_NOT_ALLOWED", ex.code());
    }

    @Test
    void createFailsWhenGitHubPrInvalid() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("Test PR");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        when(mergeRequestMapper.selectOne(any())).thenReturn(null);

        when(gitCredentialService.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("mock-grant-id");
        qg.qgent.orchestration.worker.WorkerGitPushResponse mockPushResponse = new qg.qgent.orchestration.worker.WorkerGitPushResponse()
                .setBranch("feature/test")
                .setHeadCommit("sha123")
                .setVerified(true);
        when(sandboxWorkerClient.pushWorkspaceBranch(any(), any(), any())).thenReturn(mockPushResponse);

        // GitHub returns invalid PR (number = 0 or null etc.)
        GitHubPullRequestDetails githubPr = new GitHubPullRequestDetails(
                0L, 0, "open", "Test PR", "sha123", "feature/test", "main", false, "url"
        );
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any(GitHubPullRequestCreateRequest.class)))
                .thenReturn(githubPr);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("GITHUB_PR_RESPONSE_INVALID", ex.code());

        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
    }

    @Test
    void createFailsWhenOpenPrExists() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        MergeRequestEntity existing = new MergeRequestEntity();
        existing.setId(UUID.randomUUID());
        existing.setHeadCommit("sha456"); // Different commit means it's an error to recreate
        when(mergeRequestMapper.selectOne(any())).thenReturn(existing);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("OPEN_MR_ALREADY_EXISTS", ex.code());
    }

    @Test
    void syncUpdatesStatusAndSha() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(repositoryId);
        mr.setStatus("OPEN");
        mr.setProviderNumber(100L);
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        GitHubPullRequestDetails githubPr = new GitHubPullRequestDetails(
                1L, 100, "closed", "Updated Title", "sha999", "feature/test", "main", true, "url"
        );
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenReturn(githubPr);

        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of());

        MergeRequestSummaryResponse response = service.sync(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("MERGED", response.getStatus());
        assertEquals("sha999", response.getHeadCommit());
        assertEquals("Updated Title", response.getTitle());

        verify(mergeRequestMapper, atLeastOnce()).updateById(mr);
        verify(eventService).publish(any(), any(), eq("merge-request.updated"), any(), any());
    }

    @Test
    void syncKeepsLocalOpenWhenUnmerged() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(repositoryId);
        mr.setStatus("OPEN");
        mr.setProviderNumber(100L);
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner");
        githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        GitHubPullRequestDetails githubPr = new GitHubPullRequestDetails(
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url"
        );
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenReturn(githubPr);

        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of());

        MergeRequestSummaryResponse response = service.sync(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
    }

    @Test
    void createFailsWhenInstallationNotActive() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setAuthorizationStatus("AUTHORIZED");
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("SUSPENDED"); // not active
        installation.setProviderInstallationId(12345L);
        installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("GITHUB_INSTALLATION_UNAVAILABLE", ex.code());
    }

    @Test
    void createFailsWhenRepositoryRevoked() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feature/test");
        worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setAuthorizationStatus("REVOKED"); // 已撤权
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", ex.code());
        // 已撤权仓库不得调用 GitHub
        verifyNoInteractions(githubClient);
    }

    @Test
    void syncFailsWhenRepositoryRevoked() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(repositoryId);
        mr.setStatus("OPEN");
        mr.setProviderNumber(100L);
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setAuthorizationStatus("REVOKED"); // 已撤权
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ApiException ex = assertThrows(ApiException.class, () -> service.sync(projectId, mergeRequestId, userId));
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", ex.code());
        // 已撤权仓库不得调用 GitHub
        verifyNoInteractions(githubClient);
    }

    @Test
    void mergeFailsWhenRepositoryRevoked() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(repositoryId);
        mr.setStatus("OPEN");
        mr.setProviderNumber(100L);
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId());
        githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setAuthorizationStatus("REVOKED"); // 已撤权
        githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);

        ApiException ex = assertThrows(ApiException.class, () -> service.merge(projectId, mergeRequestId, userId));
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", ex.code());
        // 已撤权仓库不得调用 GitHub
        verifyNoInteractions(githubClient);
    }

    @Test
    void cqRejectionSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setAuthorUserId(authorId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        mr.setHeadCommit("sha123");
        mr.setTargetBranch("main");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        MergeRequestSummaryResponse response = service.cqRejection(projectId, mergeRequestId, userId, "Needs changes");

        assertNotNull(response);
        verify(qualityCheckMapper).insert(any(QualityCheckResultEntity.class));
        verify(eventService).publish(any(), any(), eq("merge-request.updated"), any(), any());
    }

    @Test
    void cqRejectionFailsWhenReasonEmpty() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setAuthorUserId(authorId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        ApiException ex = assertThrows(ApiException.class, () -> service.cqRejection(projectId, mergeRequestId, userId, ""));
        assertEquals("CQ_REJECTION_REASON_REQUIRED", ex.code());
    }

    @Test
    void detailReturnsCorrectGatePassed() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        UUID testsetId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        mr.setTargetBranch("main");
        mr.setHeadCommit("sha123");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        config.setId(UUID.randomUUID());
        config.setProjectRepositoryId(mr.getProjectRepositoryId());
        config.setBranchName(mr.getTargetBranch());
        config.setRequiredChecks(java.util.List.of("AI_REVIEW", "TESTSET"));
        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of(config));

        RepositoryBranchConfigTestsetEntity ts = new RepositoryBranchConfigTestsetEntity();
        ts.setTestsetId(testsetId);
        ts.setBranchConfigId(config.getId());
        when(branchConfigTestsetMapper.selectList(any())).thenReturn(java.util.List.of(ts));

        QualityCheckResultEntity r1 = new QualityCheckResultEntity();
        r1.setMergeRequestId(mr.getId());
        r1.setCommitSha(mr.getHeadCommit());
        r1.setCheckType("TESTSET");
        r1.setTestsetId(testsetId);
        r1.setStatus("PASSED");
        
        QualityCheckResultEntity r2 = new QualityCheckResultEntity();
        r2.setMergeRequestId(mr.getId());
        r2.setCommitSha(mr.getHeadCommit());
        r2.setCheckType("AI_REVIEW");
        r2.setStatus("PASSED");
        when(qualityCheckMapper.selectList(any())).thenReturn(java.util.List.of(r1, r2));

        qg.qgent.dto.MergeRequestDetailResponse response = service.detail(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("PASSED", response.getQualityGate().getStatus());
    }

    @Test
    void detailReturnsCorrectGateFailed() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        mr.setTargetBranch("main");
        mr.setHeadCommit("sha123");
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        config.setId(UUID.randomUUID());
        config.setProjectRepositoryId(mr.getProjectRepositoryId());
        config.setBranchName(mr.getTargetBranch());
        config.setRequiredChecks(java.util.List.of("CQ_PLUS_ONE"));
        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of(config));

        QualityCheckResultEntity r1 = new QualityCheckResultEntity();
        r1.setMergeRequestId(mr.getId());
        r1.setCommitSha(mr.getHeadCommit());
        r1.setCheckType("CQ_PLUS_ONE");
        r1.setStatus("FAILED");
        when(qualityCheckMapper.selectList(any())).thenReturn(java.util.List.of(r1));

        qg.qgent.dto.MergeRequestDetailResponse response = service.detail(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("FAILED", response.getQualityGate().getStatus());
    }

    @Test
    void listReturnsEmptyWhenNoRepos() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(projectRepositoryMapper.selectList(any())).thenReturn(java.util.List.of());

        qg.qgent.dto.ApiPageResponse<MergeRequestSummaryResponse> response = service.list(projectId, userId, null, null, null, null, 10, "req-1");
        assertTrue(response.data().isEmpty());
    }

    @Test
    void checksSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        QualityCheckResultEntity c1 = new QualityCheckResultEntity();
        c1.setId(UUID.randomUUID());
        c1.setCheckType("AI_REVIEW");
        when(qualityCheckMapper.selectList(any())).thenReturn(java.util.List.of(c1));

        qg.qgent.dto.MergeRequestChecksResponse checks = service.checks(projectId, mergeRequestId, userId);
        assertEquals(1, checks.getItems().size());
        assertEquals("AI_REVIEW", checks.getItems().get(0).getType());
    }

    @Test
    void reviewsSuccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mergeRequestId = UUID.randomUUID();
        
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mergeRequestId);
        mr.setProjectRepositoryId(UUID.randomUUID());
        when(mergeRequestMapper.selectById(mergeRequestId)).thenReturn(mr);

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(mr.getProjectRepositoryId());
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(mr.getProjectRepositoryId())).thenReturn(repository);

        MergeRequestReviewEntity rev = new MergeRequestReviewEntity();
        rev.setId(UUID.randomUUID());
        rev.setDecision("APPROVED");
        when(reviewMapper.selectList(any())).thenReturn(java.util.List.of(rev));

        java.util.List<qg.qgent.dto.MergeRequestReviewResponse> reviews = service.reviews(projectId, mergeRequestId, userId);
        assertEquals(1, reviews.size());
        assertEquals("APPROVED", reviews.get(0).getDecision());
    }
}
