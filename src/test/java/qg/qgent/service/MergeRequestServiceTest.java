package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.*;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.github.GitHubPullRequestCreateRequest;
import qg.qgent.github.GitHubPullRequestDetails;
import qg.qgent.github.GitHubPullRequestMergeRequest;
import qg.qgent.github.GitHubPullRequestMergeResult;
import qg.qgent.mapper.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
    private PreflightGateService preflightGates;

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
        preflightGates = mock(PreflightGateService.class);
        service.setPreflightGates(preflightGates);
        when(preflightGates.normalizeTargetBranch(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preflightGates.resolveTargetCommit(any(), any(), anyString())).thenReturn("target-commit");
        org.mockito.Mockito.lenient().when(preflightGates.requireEvidence(any(), any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> preflightEvidence(
                        invocation.getArgument(1, WorkspaceRepositoryEntity.class).getHeadCommit(),
                        invocation.getArgument(4, String.class)));
        when(githubClient.getBranch(anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new GitHubBranchDetails(invocation.getArgument(3), "sha123"));
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
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("MR_FIRST"); task.setStatus("WAITING_PREFLIGHT");
        task.setRequirementGroupId(requirementGroupId);
        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.selectByIdForUpdate(taskId)).thenReturn(task);

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
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url", null, "unknown", "base-sha"
        );
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any(GitHubPullRequestCreateRequest.class)))
                .thenAnswer(invocation -> { assertFalse(inTransaction.get()); return githubPr; });
        // 创建 PR 时 GitHub 尚未算完 mergeable（null），轮询随后返回 clean。
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenReturn(new GitHubPullRequestDetails(
                        1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url",
                        true, "clean", "base-sha"));

        AtomicReference<MergeRequestEntity> inserted = new AtomicReference<>();
        when(mergeRequestMapper.insert(any(MergeRequestEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(mergeRequestMapper.selectById(any())).thenAnswer(invocation -> inserted.get());
        DiffEntity accepted = new DiffEntity();
        accepted.setId(UUID.randomUUID()); accepted.setDeliveryStatus("PUSHED");
        when(diffs.selectAcceptedCommittedForMr(eq(taskId), eq(projectId), eq(workspaceId), eq(repositoryId),
                eq("sha123"))).thenReturn(accepted);
        doAnswer(invocation -> {
            accepted.setDeliveryStatus("MR_CREATED");
            return 1;
        }).when(diffs).markDelivered(eq(accepted.getId()), any());
        when(diffs.selectList(any())).thenReturn(java.util.List.of(accepted));
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService orchestrator = mock(OrchestratorAgentService.class);
        UUID cardAgentId = UUID.randomUUID();
        when(orchestrator.resolveIdForTask(task)).thenReturn(cardAgentId);
        service.setMessageService(messages);
        service.setOrchestratorAgents(orchestrator);

        MergeRequestSummaryResponse response = service.create(projectId, userId, request);

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
        assertEquals("feature/test", response.getSourceBranch());
        assertEquals(100L, response.getNumber());
        verify(sandboxWorkerClient, never()).pushWorkspaceBranch(any(), any(), any());
        // 创建后自动轮询把 GitHub mergeable 落库并体现在摘要中。
        assertEquals(Boolean.TRUE, response.getMergeable());
        assertEquals("clean", response.getMergeableState());

        verify(mergeRequestMapper).insert(any(MergeRequestEntity.class));
        verify(mergeRequestMapper, atLeastOnce()).selectByIdForUpdate(inserted.get().getId());
        verify(mergeRequestGroupMapper).insert(any(MergeRequestGroupEntity.class));
        ArgumentCaptor<QualityCheckResultEntity> preflightChecks = ArgumentCaptor.forClass(QualityCheckResultEntity.class);
        verify(qualityCheckMapper, times(2)).insert(preflightChecks.capture());
        assertEquals(java.util.Set.of("DRY_RUN", "CQ_PLUS_ONE"), preflightChecks.getAllValues().stream()
                .map(QualityCheckResultEntity::getCheckType).collect(java.util.stream.Collectors.toSet()));
        assertTrue(preflightChecks.getAllValues().stream().allMatch(check -> "PASSED".equals(check.getStatus())
                && "sha123".equals(check.getCommitSha()) && check.getSource().startsWith("PREFLIGHT_")));
        verify(diffs).markDelivered(eq(accepted.getId()), any());
        assertEquals("SUCCEEDED", task.getStatus());
        verify(taskMapper).updateById(task);
        ArgumentCaptor<Map<String, Object>> deliveryEvent = ArgumentCaptor.forClass(Map.class);
        verify(eventService).publish(eq(projectId), eq(requirementGroupId), eq("delivery.repository.updated"),
                eq(accepted.getId().toString()), deliveryEvent.capture());
        assertEquals(repositoryId, deliveryEvent.getValue().get("repositoryId"));
        // 创建后 publish 一次，轮询落库 mergeable 后再 publish 一次。
        verify(eventService, times(2)).publish(any(), any(), eq("merge-request.updated"), any(), any());
        ArgumentCaptor<MessageSendRequest> mrCard = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(messages).upsertTaskStatusCard(eq(requirementGroupId), eq(cardAgentId), mrCard.capture());
        assertEquals("TASK_STATUS", mrCard.getValue().getType());
        assertEquals("MR_CREATED", mrCard.getValue().getContent().get("status"));
        assertEquals(repositoryId.toString(), mrCard.getValue().getContent().get("repositoryId"));
        Map<String, Object> cardMr = (Map<String, Object>) mrCard.getValue().getContent().get("mergeRequest");
        assertEquals(100L, cardMr.get("number"));
    }

    @Test
    void createReplacesStaleLocalOpenMrWhenGithubNoLongerHasIt() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("Replacement PR");

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("DIFF_FIRST");
        task.setStatus("SUCCEEDED");
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

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId());
        installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L);
        UUID teamId = UUID.randomUUID();
        installation.setTeamId(teamId);
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);

        MergeRequestEntity stale = new MergeRequestEntity();
        stale.setId(UUID.randomUUID());
        stale.setProjectRepositoryId(repositoryId);
        stale.setProviderNumber(99L);
        stale.setSourceBranch("feature/test");
        stale.setTargetBranch("main");
        stale.setHeadCommit("sha123");
        stale.setStatus("OPEN");
        AtomicReference<MergeRequestEntity> storedLocalMr = new AtomicReference<>(stale);
        AtomicReference<String> retiredStatus = new AtomicReference<>();
        when(mergeRequestMapper.selectByIdForUpdate(any())).thenAnswer(invocation -> storedLocalMr.get());
        when(mergeRequestMapper.selectOne(any())).thenAnswer(invocation ->
                "OPEN".equals(storedLocalMr.get().getStatus()) ? storedLocalMr.get() : null);
        when(mergeRequestMapper.updateById(any(MergeRequestEntity.class))).thenAnswer(invocation -> {
            MergeRequestEntity value = invocation.getArgument(0);
            if (stale.getId().equals(value.getId())) retiredStatus.set(value.getStatus());
            storedLocalMr.set(value);
            return 1;
        });

        GitHubPullRequestDetails replacement = new GitHubPullRequestDetails(
                2L, 100, "open", "Replacement PR", "sha123", "feature/test", "main", false,
                "url", null, "unknown", "base-sha");
        when(githubClient.findOpenPullRequest(12345L, "owner", "repo", "feature/test", "main"))
                .thenReturn(null);
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any()))
                .thenReturn(replacement);

        AtomicReference<MergeRequestEntity> inserted = new AtomicReference<>();
        when(mergeRequestMapper.insert(any(MergeRequestEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(mergeRequestMapper.selectById(any())).thenAnswer(invocation -> inserted.get());

        MergeRequestSummaryResponse response = service.create(projectId, userId, request);

        assertEquals("CLOSED", retiredStatus.get());
        assertNotNull(response);
        assertEquals(100L, response.getNumber());
        verify(mergeRequestMapper).updateById(stale);
        verify(githubClient).createPullRequest(eq(12345L), eq("owner"), eq("repo"), any());
    }

    @Test
    void createIsBlockedByPreflightBeforeAnyGithubCall() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(UUID.randomUUID());
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("blocked");

        PreflightGateService preflight = mock(PreflightGateService.class);
        service.setPreflightGates(preflight);
        when(preflight.normalizeTargetBranch(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preflight.resolveTargetCommit(projectId, repositoryId, "main")).thenReturn("target-commit");

        TaskEntity task = new TaskEntity();
        task.setId(request.getTaskId());
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(UUID.randomUUID());
        task.setDeliveryMode("DIFF_FIRST");
        task.setStatus("SUCCEEDED");
        when(taskMapper.selectById(task.getId())).thenReturn(task);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feature/blocked");
        worktree.setHeadCommit("source-commit");
        when(workspaceRepositoryMapper.selectForUpdate(task.getWorkspaceId(), repositoryId)).thenReturn(worktree);
        doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "MR_PREFLIGHT_NOT_PASSED", "blocked"))
                .when(preflight).requireReady(task, worktree, repositoryId, "main", "target-commit");

        ApiException error = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));

        assertEquals("MR_PREFLIGHT_NOT_PASSED", error.code());
        verifyNoInteractions(githubClient);
    }

    @Test
    void createRejectsWhenTargetBranchChangesAfterPreflight() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("stale target");

        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("MR_FIRST"); task.setStatus("WAITING_PREFLIGHT");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feature/test"); worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);
        when(mergeRequestMapper.selectOne(any())).thenReturn(null);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId()); githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner"); githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED"); githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);
        ProjectEntity project = new ProjectEntity(); project.setId(projectId); project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId()); installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L); installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        PreflightGateService preflight = mock(PreflightGateService.class);
        service.setPreflightGates(preflight);
        when(preflight.normalizeTargetBranch(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preflight.resolveTargetCommit(projectId, repositoryId, "main"))
                .thenReturn("target-before", "target-after");

        ApiException error = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));

        assertEquals("PREFLIGHT_CONTEXT_STALE", error.code());
        verify(githubClient, never()).createPullRequest(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void existingMrWithChangedHeadDoesNotCompleteTaskBeforeRemoteContextIsVerified() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setTaskId(taskId);
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        request.setTitle("stale source");

        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("MR_FIRST"); task.setStatus("WAITING_PREFLIGHT");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setRepositoryId(UUID.randomUUID());
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(repository);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feature/test"); worktree.setHeadCommit("sha123");
        when(workspaceRepositoryMapper.selectForUpdate(workspaceId, repositoryId)).thenReturn(worktree);
        MergeRequestEntity existing = new MergeRequestEntity();
        existing.setId(UUID.randomUUID()); existing.setHeadCommit("old-sha");
        when(mergeRequestMapper.selectOne(any())).thenReturn(existing);

        GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
        githubRepository.setId(repository.getRepositoryId()); githubRepository.setInstallationId(UUID.randomUUID());
        githubRepository.setOwnerLogin("owner"); githubRepository.setName("repo");
        githubRepository.setAuthorizationStatus("AUTHORIZED"); githubRepository.setArchived(false);
        when(githubRepositoryMapper.selectById(repository.getRepositoryId())).thenReturn(githubRepository);
        ProjectEntity project = new ProjectEntity(); project.setId(projectId); project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(githubRepository.getInstallationId()); installation.setStatus("ACTIVE");
        installation.setProviderInstallationId(12345L); installation.setTeamId(project.getTeamId());
        when(githubInstallationMapper.selectById(githubRepository.getInstallationId())).thenReturn(installation);

        PreflightGateService preflight = mock(PreflightGateService.class);
        service.setPreflightGates(preflight);
        when(preflight.normalizeTargetBranch(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preflight.resolveTargetCommit(projectId, repositoryId, "main")).thenReturn("target-commit");
        when(githubClient.getBranch(12345L, "owner", "repo", "feature/test"))
                .thenReturn(new GitHubBranchDetails("feature/test", "newer-sha"));

        ApiException error = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));

        assertEquals("MR_SOURCE_HEAD_CHANGED", error.code());
        verify(diffs, never()).markDelivered(any(), any());
        verify(taskMapper, never()).updateById(task);
    }

    @Test
    void mrFirstCannotCreateMrFromPartiallyFailedDelivery() {
        TaskEntity task = new TaskEntity();
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("DELIVERY_FAILED");
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setHeadCommit("current-head");

        ApiException error = assertThrows(ApiException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "requireTaskReadyForMr", task, worktree));

        assertEquals("MR_TASK_NOT_WAITING_PREFLIGHT", error.code());
    }

    @Test
    void completedMrFirstTaskCanReplayMatchingExistingMr() {
        TaskEntity task = new TaskEntity();
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("SUCCEEDED");
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setHeadCommit("current-head");
        MergeRequestEntity existing = new MergeRequestEntity();
        existing.setHeadCommit("current-head");

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service,
                "requireTaskReadyForMr", task, worktree));
    }

    @Test
    void mrFirstTaskCompletesOnlyAfterEveryRepositoryHasCreatedMr() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID firstRepositoryId = UUID.randomUUID();
        UUID secondRepositoryId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("WAITING_PREFLIGHT");
        when(taskMapper.selectByIdForUpdate(taskId)).thenReturn(task);

        DiffEntity first = acceptedDiff(firstRepositoryId, "first-head");
        DiffEntity second = acceptedDiff(secondRepositoryId, "second-head");
        when(diffs.selectAcceptedCommittedForMr(eq(taskId), eq(projectId), eq(workspaceId),
                eq(firstRepositoryId), eq("first-head"))).thenReturn(first);
        when(diffs.selectAcceptedCommittedForMr(eq(taskId), eq(projectId), eq(workspaceId),
                eq(secondRepositoryId), eq("second-head"))).thenReturn(second);
        when(diffs.selectList(any())).thenReturn(java.util.List.of(first, second));
        doAnswer(invocation -> {
            DiffEntity value = invocation.getArgument(0).equals(first.getId()) ? first : second;
            value.setDeliveryStatus("MR_CREATED");
            return 1;
        }).when(diffs).markDelivered(any(), any());

        Object firstClaim = createClaim(task, firstRepositoryId, "first-head");
        Object firstResult = ReflectionTestUtils.invokeMethod(service, "markMrCreatedAndCompleteTask", firstClaim);
        assertNull(firstResult);
        assertEquals("WAITING_PREFLIGHT", task.getStatus());
        verify(taskMapper, never()).updateById(task);

        Object secondClaim = createClaim(task, secondRepositoryId, "second-head");
        TaskEntity completed = ReflectionTestUtils.invokeMethod(service, "markMrCreatedAndCompleteTask", secondClaim);
        assertSame(task, completed);
        assertEquals("SUCCEEDED", task.getStatus());
        verify(taskMapper).updateById(task);
    }

    private DiffEntity acceptedDiff(UUID repositoryId, String headCommit) {
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID());
        diff.setProjectRepositoryId(repositoryId);
        diff.setHeadCommit(headCommit);
        diff.setDeliveryStatus("PUSHED");
        return diff;
    }

    private Object createClaim(TaskEntity task, UUID repositoryId, String headCommit) throws Exception {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setHeadCommit(headCommit);
        MergeRequestCreateRequest request = new MergeRequestCreateRequest();
        request.setRepositoryId(repositoryId);
        request.setTargetBranch("main");
        Class<?> claimType = Class.forName("qg.qgent.service.MergeRequestService$CreateClaim");
        java.lang.reflect.Constructor<?> constructor = claimType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(task, worktree, null, null, request, null, null, null, "target-commit");
    }

    private PreflightGateService.PreflightEvidence preflightEvidence(String sourceCommit, String targetCommit) {
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(UUID.randomUUID());
        dryRun.setStatus("PASSED");
        dryRun.setHeadCommit(sourceCommit);
        dryRun.setTargetBranch("main");
        dryRun.setResolvedTargetCommit(targetCommit);
        PreflightCqReviewEntity cq = new PreflightCqReviewEntity();
        cq.setId(UUID.randomUUID());
        cq.setDecision("APPROVED");
        cq.setReviewerUserId(UUID.randomUUID());
        cq.setSourceCommit(sourceCommit);
        cq.setTargetBranch("main");
        cq.setTargetCommit(targetCommit);
        return new PreflightGateService.PreflightEvidence(dryRun, cq);
    }

    @Test
    void preflightProjectionReplayDoesNotInsertDuplicateChecks() throws Exception {
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(UUID.randomUUID());
        mr.setHeadCommit("source-commit");
        PreflightGateService.PreflightEvidence evidence = preflightEvidence("source-commit", "target-commit");
        QualityCheckResultEntity existing = new QualityCheckResultEntity();
        existing.setId(UUID.randomUUID());
        when(qualityCheckMapper.selectOne(any())).thenReturn(null, null, null, null, existing, existing);

        invokePrivate("projectPreflightChecks", mr, evidence);
        invokePrivate("projectPreflightChecks", mr, evidence);

        verify(qualityCheckMapper, times(2)).insert(any(QualityCheckResultEntity.class));
    }

    @Test
    void stalePreflightEvidenceDuringFinalizationDoesNotProjectPassedChecks() throws Exception {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID()); task.setProjectId(UUID.randomUUID()); task.setWorkspaceId(UUID.randomUUID());
        task.setDeliveryMode("MR_FIRST"); task.setStatus("WAITING_PREFLIGHT");
        UUID repositoryId = UUID.randomUUID();
        Object claim = createClaim(task, repositoryId, "source-commit");
        ApiException stale = new ApiException(org.springframework.http.HttpStatus.CONFLICT, "MR_PREFLIGHT_NOT_PASSED",
                "预检已失效");
        PreflightGateService stalePreflight = mock(PreflightGateService.class);
        service.setPreflightGates(stalePreflight);
        org.mockito.Mockito.doAnswer(invocation -> {
            throw stale;
        }).when(stalePreflight).requireEvidence(any(), any(), any(), anyString(), anyString());
        GitHubPullRequestDetails remote = new GitHubPullRequestDetails(1L, 7, "open", "title", "source-commit",
                "feature/test", "main", false, "url", null, "unknown", "target-commit");

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> invokePrivate("finalizeCreate", claim, remote));

        assertSame(stale, failure.getCause());
        verify(qualityCheckMapper, never()).insert(any(QualityCheckResultEntity.class));
        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
    }

    private Object invokePrivate(String name, Object... arguments) throws Exception {
        Method method = java.util.Arrays.stream(MergeRequestService.class.getDeclaredMethods())
                .filter(candidate -> name.equals(candidate.getName()) && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(service, arguments);
    }

    @Test
    void createSucceedsEvenWhenMergeabilityPollFails() {
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
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST"); task.setStatus("SUCCEEDED");
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
        when(sandboxWorkerClient.pushWorkspaceBranch(any(), any(), any())).thenReturn(
                new qg.qgent.orchestration.worker.WorkerGitPushResponse()
                        .setBranch("feature/test").setHeadCommit("sha123").setVerified(true));

        GitHubPullRequestDetails githubPr = new GitHubPullRequestDetails(
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url", null, "unknown", "base-sha"
        );
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any(GitHubPullRequestCreateRequest.class)))
                .thenReturn(githubPr);
        // mergeability 轮询遇到 GitHub 瞬时失败，不应让已创建的 MR 交付失败。
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE", "boom"));

        AtomicReference<MergeRequestEntity> inserted = new AtomicReference<>();
        when(mergeRequestMapper.insert(any(MergeRequestEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(mergeRequestMapper.selectById(any())).thenAnswer(invocation -> inserted.get());

        MergeRequestSummaryResponse response = service.create(projectId, userId, request);

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
        assertEquals(100L, response.getNumber());
        // 轮询失败时回退到创建态，mergeable 保持 null，但不抛错、不把交付标记为失败。
        assertNull(response.getMergeable());
        verify(eventService, times(1)).publish(any(), any(), eq("merge-request.updated"), any(), any());
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
    void pushAcceptedBranchFailsWhenWorkerPushVerificationFails() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setCreatedBy(userId);
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST"); task.setStatus("SUCCEEDED");
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
        when(workspaceRepositoryMapper.selectByWorkspace(workspaceId)).thenReturn(java.util.List.of(worktree));
        when(diffs.selectAcceptedCommittedForPush(taskId, projectId, workspaceId, repositoryId, "sha123"))
                .thenReturn(new DiffEntity());

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

        when(gitCredentialService.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("mock-grant-id");
        
        qg.qgent.orchestration.worker.WorkerGitPushResponse mockPushResponse = new qg.qgent.orchestration.worker.WorkerGitPushResponse()
                .setBranch("feature/test")
                .setHeadCommit("sha123")
                .setVerified(false); // verified=false
        when(sandboxWorkerClient.pushWorkspaceBranch(any(), any(), any())).thenReturn(mockPushResponse);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.pushAcceptedBranch(projectId, taskId, repositoryId));
        assertEquals("WORKER_PUSH_VERIFICATION_FAILED", ex.code());
        verify(diffs).selectAcceptedCommittedForPush(taskId, projectId, workspaceId, repositoryId, "sha123");
        verify(diffs, never()).selectAcceptedCommittedForMr(any(), any(), any(), any(), any());
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
    void mergeRejectsConflictingPullRequest() {
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

        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of());

        GitHubPullRequestDetails conflicting = new GitHubPullRequestDetails(
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url",
                false, "dirty", "base-sha"
        );
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenReturn(conflicting);

        ApiException ex = assertThrows(ApiException.class, () -> service.merge(projectId, mergeRequestId, userId));
        assertEquals("MR_HAS_CONFLICTS", ex.code());
        assertTrue(ex.getMessage().contains("dirty"));

        verify(githubClient, never()).mergePullRequest(anyLong(), anyString(), anyString(), anyInt(),
                any(GitHubPullRequestMergeRequest.class));
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
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST"); task.setStatus("SUCCEEDED");
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
                0L, 0, "open", "Test PR", "sha123", "feature/test", "main", false, "url", null, "unknown", "base-sha"
        );
        when(githubClient.createPullRequest(eq(12345L), eq("owner"), eq("repo"), any(GitHubPullRequestCreateRequest.class)))
                .thenReturn(githubPr);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(projectId, userId, request));
        assertEquals("GITHUB_PR_RESPONSE_INVALID", ex.code());

        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
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
                1L, 100, "closed", "Updated Title", "sha999", "feature/test", "main", true, "url",
                null, "unknown", "base-sha"
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
                1L, 100, "open", "Test PR", "sha123", "feature/test", "main", false, "url",
                true, "clean", "base-sha"
        );
        when(githubClient.getPullRequest(eq(12345L), eq("owner"), eq("repo"), eq(100)))
                .thenReturn(githubPr);

        when(branchConfigMapper.selectList(any())).thenReturn(java.util.List.of());

        MergeRequestSummaryResponse response = service.sync(projectId, mergeRequestId, userId);

        assertNotNull(response);
        assertEquals("OPEN", response.getStatus());
        assertEquals(Boolean.TRUE, response.getMergeable());
        assertEquals("clean", response.getMergeableState());
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
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST"); task.setStatus("SUCCEEDED");
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
        task.setWorkspaceId(workspaceId); task.setDeliveryMode("DIFF_FIRST"); task.setStatus("SUCCEEDED");
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
    void listInjectsDeterministicPendingCreatePlaceholderForWaitingPreflightTask() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setDefaultBranch("main");
        when(projectRepositoryMapper.selectList(any())).thenReturn(java.util.List.of(repository));
        when(projectRepositoryMapper.selectBatchIds(anyCollection())).thenReturn(java.util.List.of(repository));

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseRef("main");
        worktree.setSourceBranch("feat/task-pending");
        worktree.setHeadCommit("head-sha");
        when(workspaceRepositoryMapper.selectByProject(projectId, null)).thenReturn(java.util.List.of(worktree));

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setStatus("WAITING_PREFLIGHT");
        task.setDeliveryMode("MR_FIRST");
        task.setTitle("待创建 MR");
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of(task));
        when(mergeRequestMapper.selectList(any())).thenReturn(java.util.List.of());

        var response = service.list(projectId, userId, null, null, null, null, 20, "req-1");

        assertEquals(1, response.data().size());
        MergeRequestSummaryResponse row = response.data().get(0);
        assertEquals("PENDING_CREATE", row.getStatus());
        assertEquals(taskId.toString(), row.getTaskId());
        assertEquals("SYSTEM", row.getCreateMode());
        assertEquals(repositoryId.toString(), row.getRepositoryId());
        assertEquals("feat/task-pending", row.getSourceBranch());
        assertEquals("main", row.getTargetBranch());
        assertEquals(0L, row.getNumber());
        assertNull(row.getWebUrl());

        var again = service.list(projectId, userId, null, null, null, null, 20, "req-1");
        assertEquals(row.getId(), again.data().get(0).getId());
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
