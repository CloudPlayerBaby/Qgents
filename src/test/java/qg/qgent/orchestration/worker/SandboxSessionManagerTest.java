package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import qg.qgent.api.ApiException;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.service.GitCredentialService;

/**
 * {@link SandboxSessionManager} 单元测试：验证 provision/创建 Sandbox 的字段映射、
 * 会话复用、release 销毁与未启用时的 no-op，不启动 Spring 上下文、不访问真实 Worker。
 */
class SandboxSessionManagerTest {

    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SandboxWorkerClient client;
    private WorkspaceMapper workspaceMapper;
    private WorkspaceRepositoryMapper repositoryMapper;
    private ProjectRepositoryMapper projectRepositoryMapper;
    private GitHubRepositoryMapper gitHubRepositoryMapper;
    private GitHubInstallationMapper installationMapper;
    private GitCredentialService credentialService;
    private qg.qgent.github.GitHubAppClient githubAppClient;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        repositoryMapper = mock(WorkspaceRepositoryMapper.class);
        projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
        gitHubRepositoryMapper = mock(GitHubRepositoryMapper.class);
        installationMapper = mock(GitHubInstallationMapper.class);
        credentialService = mock(GitCredentialService.class);
        githubAppClient = mock(qg.qgent.github.GitHubAppClient.class);
    }

    private SandboxSessionManager enabledManager() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setEnabled(true);
        return new SandboxSessionManager(client, properties, workspaceMapper, repositoryMapper,
                projectRepositoryMapper, gitHubRepositoryMapper, installationMapper, credentialService, githubAppClient);
    }

    private SandboxSessionManager enabledManagerZeroBackoff() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setEnabled(true);
        properties.setAcquireInitialBackoff(Duration.ZERO);
        return new SandboxSessionManager(client, properties, workspaceMapper, repositoryMapper,
                projectRepositoryMapper, gitHubRepositoryMapper, installationMapper, credentialService, githubAppClient);
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(WORKSPACE);
        entity.setProjectId(PROJECT);
        entity.setStorageKey("workspaces/" + WORKSPACE);
        return entity;
    }

    private WorkspaceRepositoryEntity repository() {
        WorkspaceRepositoryEntity entity = new WorkspaceRepositoryEntity();
        entity.setWorkspaceId(WORKSPACE);
        entity.setProjectRepositoryId(REPO);
        entity.setWorkspacePath("repo-1");
        entity.setBaseCommit("main");
        entity.setSourceBranch("feat/task-" + TASK);
        return entity;
    }

    private void mockDependenciesForAcquire() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));

        ProjectRepositoryEntity projectRepo = new ProjectRepositoryEntity();
        projectRepo.setId(REPO);
        projectRepo.setRepositoryId(UUID.randomUUID());
        projectRepo.setDefaultBranch("main");
        projectRepo.setStatus("ACTIVE");
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(projectRepo);

        GitHubRepositoryEntity ghRepo = new GitHubRepositoryEntity();
        ghRepo.setId(projectRepo.getRepositoryId());
        ghRepo.setInstallationId(UUID.randomUUID());
        ghRepo.setOwnerLogin("owner");
        ghRepo.setName("repo");
        ghRepo.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectById(projectRepo.getRepositoryId())).thenReturn(ghRepo);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(ghRepo.getInstallationId());
        installation.setProviderInstallationId(12345L);
        installation.setStatus("ACTIVE");
        installation.setTeamId(UUID.randomUUID());
        when(installationMapper.selectById(ghRepo.getInstallationId())).thenReturn(installation);

        when(githubAppClient.getBranch(eq(12345L), eq("owner"), eq("repo"), eq("main")))
                .thenReturn(new qg.qgent.github.GitHubBranchDetails("main", "a".repeat(40)));

        when(credentialService.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("mock-grant");

        when(client.syncGitStore(any(), any())).thenReturn(syncResponse(REPO, "main", "a".repeat(40)));
    }

    private WorkerGitStoreSyncResponse syncResponse(UUID repositoryId, String remoteBranch, String headCommit) {
        return new WorkerGitStoreSyncResponse()
                .setRepositoryId(repositoryId)
                .setRemoteBranch(remoteBranch)
                .setHeadCommit(headCommit);
    }

    @Test
    void disabledManagerIsNoOp() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        SandboxSessionManager manager = new SandboxSessionManager(client, properties, workspaceMapper, repositoryMapper,
                projectRepositoryMapper, gitHubRepositoryMapper, installationMapper, credentialService, githubAppClient);

        assertThat(manager.acquire(TASK, PROJECT, WORKSPACE)).isNull();
        manager.release(WORKSPACE);
        verify(client, never()).provisionWorkspace(any(), any());
        verify(client, never()).createSandbox(any());
    }

    @Test
    void acquireProvisionsAndCreatesSandbox() {
        mockDependenciesForAcquire();
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        SandboxSession session = enabledManager().acquire(TASK, PROJECT, WORKSPACE);

        ArgumentCaptor<WorkerWorkspaceProvisionRequest> provisionCaptor =
                ArgumentCaptor.forClass(WorkerWorkspaceProvisionRequest.class);
        verify(client).provisionWorkspace(org.mockito.ArgumentMatchers.eq(WORKSPACE), provisionCaptor.capture());
        assertThat(provisionCaptor.getValue().getProjectId()).isEqualTo(PROJECT);
        assertThat(provisionCaptor.getValue().getRepositories()).hasSize(1);
        WorkerWorkspaceRepositoryRequest repo = provisionCaptor.getValue().getRepositories().get(0);
        assertThat(repo.getRepositoryId()).isEqualTo(REPO);
        assertThat(repo.getBaseRef()).isEqualTo("main");
        assertThat(repo.getSourceBranch()).isEqualTo("feat/task-" + TASK);
        assertThat(repo.getWorkspacePath()).isEqualTo("repo-1");

        ArgumentCaptor<WorkerCreateSandboxRequest> createCaptor =
                ArgumentCaptor.forClass(WorkerCreateSandboxRequest.class);
        verify(client).createSandbox(createCaptor.capture());
        WorkerCreateSandboxRequest create = createCaptor.getValue();
        assertThat(create.getWorkspaceStorageKey()).isEqualTo("workspaces/" + WORKSPACE);
        assertThat(create.getTaskRunId()).isEqualTo(TASK);
        assertThat(create.getRepositoryIds()).containsExactly(REPO);
        assertThat(create.getImageProfile()).isEqualTo("java-node");

        assertThat(session.sandboxId()).isNotNull();
        assertThat(session.singleRepository()).isEqualTo(REPO);
        assertThat(session.repositoryByPath()).containsEntry("repo-1", REPO);
    }

    @Test
    void acquireReusesExistingSession() {
        mockDependenciesForAcquire();
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        SandboxSessionManager manager = enabledManager();
        SandboxSession first = manager.acquire(TASK, PROJECT, WORKSPACE);
        SandboxSession second = manager.acquire(TASK, PROJECT, WORKSPACE);

        assertThat(second).isSameAs(first);
        verify(client).provisionWorkspace(any(), any());
        verify(client).createSandbox(any());
    }

    @Test
    void acquireFailsWhenProjectRepositoryNotBound() {
        mockDependenciesForAcquire();
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(null);
        
        assertThrows(qg.qgent.api.ApiException.class, () -> enabledManager().acquire(TASK, PROJECT, WORKSPACE));
    }

    @Test
    void acquireFailsWhenProjectRepositoryIsUnbound() {
        mockDependenciesForAcquire();
        ProjectRepositoryEntity unbound = new ProjectRepositoryEntity();
        unbound.setId(REPO);
        unbound.setStatus("UNBOUND");
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(unbound);

        qg.qgent.api.ApiException exception = assertThrows(qg.qgent.api.ApiException.class,
                () -> enabledManager().acquire(TASK, PROJECT, WORKSPACE));

        assertEquals("PROJECT_REPOSITORY_NOT_BOUND", exception.code());
        verify(githubAppClient, never()).getBranch(anyLong(), any(), any(), any());
        verify(client, never()).syncGitStore(any(), any());
        verify(client, never()).provisionWorkspace(any(), any());
    }
    
    @Test
    void acquireFailsWhenGitHubRepositoryNotFound() {
        mockDependenciesForAcquire();
        when(gitHubRepositoryMapper.selectById(any())).thenReturn(null);
        
        assertThrows(qg.qgent.api.ApiException.class, () -> enabledManager().acquire(TASK, PROJECT, WORKSPACE));
    }
    
    @Test
    void acquireFailsWhenGitHubInstallationNotActive() {
        mockDependenciesForAcquire();
        GitHubInstallationEntity inactive = new GitHubInstallationEntity();
        inactive.setStatus("SUSPENDED");
        when(installationMapper.selectById(any())).thenReturn(inactive);
        
        assertThrows(qg.qgent.api.ApiException.class, () -> enabledManager().acquire(TASK, PROJECT, WORKSPACE));
    }

    @Test
    void acquireFetchesBranchWhenBaseCommitMissing() {
        mockDependenciesForAcquire();
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        WorkspaceRepositoryEntity noBase = repository();
        noBase.setBaseCommit(null);
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(noBase));
        
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(REPO);
        binding.setRepositoryId(UUID.randomUUID());
        binding.setDefaultBranch("main");
        binding.setStatus("ACTIVE");
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(binding);
        
        GitHubRepositoryEntity ghRepo = new GitHubRepositoryEntity();
        ghRepo.setId(binding.getRepositoryId());
        ghRepo.setInstallationId(UUID.randomUUID());
        ghRepo.setOwnerLogin("owner");
        ghRepo.setName("repo");
        ghRepo.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectById(binding.getRepositoryId())).thenReturn(ghRepo);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(ghRepo.getInstallationId());
        installation.setProviderInstallationId(12345L);
        installation.setStatus("ACTIVE");
        installation.setTeamId(UUID.randomUUID());
        when(installationMapper.selectById(ghRepo.getInstallationId())).thenReturn(installation);
        
        when(githubAppClient.getBranch(eq(12345L), eq("owner"), eq("repo"), eq("main")))
                .thenReturn(new qg.qgent.github.GitHubBranchDetails("main", "b".repeat(40)));
        when(client.syncGitStore(any(), any())).thenReturn(syncResponse(REPO, "main", "b".repeat(40)));

        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        SandboxSession session = enabledManager().acquire(TASK, PROJECT, WORKSPACE); // note: acquire uses TASK, PROJECT, WORKSPACE in some tests. Wait, does it?
        
        verify(githubAppClient).getBranch(12345L, "owner", "repo", "main");
        verify(credentialService).generateGrant(any(UUID.class), any(UUID.class), eq(12345L), eq("owner/repo"), eq("main"), eq("b".repeat(40)), eq(qg.qgent.entity.GitCredentialPurpose.FETCH));
        assertEquals("workspaces/" + WORKSPACE, session.storageKey());
    }

    @Test
    void acquireResolvesLegacyBaseRefBeforeWorkerSync() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        enabledManager().acquire(TASK, PROJECT, WORKSPACE);

        ArgumentCaptor<WorkerGitStoreSyncRequest> syncCaptor =
                ArgumentCaptor.forClass(WorkerGitStoreSyncRequest.class);
        verify(client).syncGitStore(eq(REPO), syncCaptor.capture());
        assertEquals("main", syncCaptor.getValue().getRemoteBranch());
        assertEquals("a".repeat(40), syncCaptor.getValue().getExpectedHeadCommit());
    }

    @Test
    void requireThrowsWithoutSession() {
        SandboxSessionManager manager = enabledManager();
        assertThatThrownBy(() -> manager.require(WORKSPACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sandbox session");
    }

    @Test
    void releaseDestroysSandboxAndRemovesSession() {
        mockDependenciesForAcquire();
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        SandboxSessionManager manager = enabledManager();
        SandboxSession session = manager.acquire(TASK, PROJECT, WORKSPACE);

        manager.release(WORKSPACE);

        verify(client).destroySandbox(session.sandboxId());
        assertThatThrownBy(() -> manager.require(WORKSPACE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void renewsActiveSessionEvenWhenNoToolIsRunning() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        SandboxSessionManager manager = enabledManager();
        SandboxSession session = manager.acquire(TASK, PROJECT, WORKSPACE);

        manager.renewActiveLeases();

        verify(client).renewSandbox(session.sandboxId());
    }

    @Test
    void doesNotRenewReleasedSession() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        SandboxSessionManager manager = enabledManager();
        SandboxSession session = manager.acquire(TASK, PROJECT, WORKSPACE);
        manager.release(WORKSPACE);

        manager.renewActiveLeases();

        verify(client, never()).renewSandbox(session.sandboxId());
    }

    @Test
    void syncFirstFailsSecondSucceeds() {
        mockDependenciesForAcquire();
        when(client.syncGitStore(any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GIT_STORE_FETCH_FAILED", "transient"))
                .thenReturn(syncResponse(REPO, "main", "a".repeat(40)));

        SandboxSession session = enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE);

        verify(client, times(2)).syncGitStore(any(), any());
        verify(client).provisionWorkspace(any(), any());
        assertThat(session.storageKey()).isEqualTo("workspaces/" + WORKSPACE);
    }

    @Test
    void baseRefNotFoundRetriesToSucceed() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "GIT_BASE_REF_NOT_FOUND", "base not ready"))
                .thenReturn(provisioned);

        enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE);

        verify(client, times(2)).provisionWorkspace(any(), any());
        verify(client, times(2)).syncGitStore(any(), any());
        verify(credentialService, times(2)).generateGrant(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonRetryableErrorIsAttemptedOnce() {
        mockDependenciesForAcquire();
        when(client.syncGitStore(any(), any()))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GIT_REMOTE_BRANCH_NOT_FOUND", "nope"));

        ApiException exception = assertThrows(ApiException.class,
                () -> enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE));

        assertEquals("GIT_REMOTE_BRANCH_NOT_FOUND", exception.code());
        verify(client, times(1)).syncGitStore(any(), any());
        verify(client, never()).provisionWorkspace(any(), any());
    }

    @Test
    void afterMultiRepoFailureNextRoundSyncsAll() {
        mockDependenciesForAcquire();
        UUID repoB = UUID.fromString("00000000-0000-0000-0000-000000000005");
        WorkspaceRepositoryEntity repoBEntity = new WorkspaceRepositoryEntity();
        repoBEntity.setWorkspaceId(WORKSPACE);
        repoBEntity.setProjectRepositoryId(repoB);
        repoBEntity.setWorkspacePath("repo-2");
        repoBEntity.setBaseCommit("main");
        repoBEntity.setSourceBranch("feat/task-2");
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository(), repoBEntity));
        stubRepositoryBinding(repoB);
        // repoA 恒成功；repoB 首轮失败、第二轮成功
        when(client.syncGitStore(eq(REPO), any())).thenReturn(syncResponse(REPO, "main", "a".repeat(40)));
        when(client.syncGitStore(eq(repoB), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GIT_STORE_FETCH_FAILED", "transient"))
                .thenReturn(syncResponse(repoB, "main", "a".repeat(40)));

        enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE);

        verify(client, times(2)).syncGitStore(eq(REPO), any());
        verify(client, times(2)).syncGitStore(eq(repoB), any());
    }

    private void stubRepositoryBinding(UUID repositoryId) {
        ProjectRepositoryEntity projectRepo = new ProjectRepositoryEntity();
        projectRepo.setId(repositoryId);
        projectRepo.setRepositoryId(UUID.randomUUID());
        projectRepo.setDefaultBranch("main");
        projectRepo.setStatus("ACTIVE");
        when(projectRepositoryMapper.selectById(repositoryId)).thenReturn(projectRepo);

        GitHubRepositoryEntity ghRepo = new GitHubRepositoryEntity();
        ghRepo.setId(projectRepo.getRepositoryId());
        ghRepo.setInstallationId(UUID.randomUUID());
        ghRepo.setOwnerLogin("owner");
        ghRepo.setName("repo");
        ghRepo.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectById(projectRepo.getRepositoryId())).thenReturn(ghRepo);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(ghRepo.getInstallationId());
        installation.setProviderInstallationId(12345L);
        installation.setStatus("ACTIVE");
        installation.setTeamId(UUID.randomUUID());
        when(installationMapper.selectById(ghRepo.getInstallationId())).thenReturn(installation);
    }

    @Test
    void createTimeoutQueriesExistingSandboxSucceeds() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        when(client.createSandbox(any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "timeout"));
        WorkerSandbox existing = new WorkerSandbox();
        existing.setTaskRunId(TASK);
        when(client.getSandbox(any())).thenReturn(existing);

        SandboxSession session = enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE);

        verify(client, times(1)).createSandbox(any());
        verify(client).getSandbox(any());
        assertThat(session.sandboxId()).isNotNull();
        assertThat(session.storageKey()).isEqualTo("workspaces/" + WORKSPACE);
    }

    @Test
    void createTimeoutWithDifferentSpecThrowsConflict() {
        mockDependenciesForAcquire();
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        when(client.createSandbox(any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "timeout"));
        WorkerSandbox existing = new WorkerSandbox();
        existing.setTaskRunId(UUID.fromString("00000000-0000-0000-0000-000000000099"));
        when(client.getSandbox(any())).thenReturn(existing);

        ApiException exception = assertThrows(ApiException.class,
                () -> enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE));

        assertEquals("SANDBOX_ID_CONFLICT", exception.code());
    }

    @Test
    void retriesExhaustedThrowsFinalException() {
        mockDependenciesForAcquire();
        when(client.syncGitStore(any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GIT_STORE_FETCH_FAILED", "always"));

        ApiException exception = assertThrows(ApiException.class,
                () -> enabledManagerZeroBackoff().acquire(TASK, PROJECT, WORKSPACE));

        assertEquals("GIT_STORE_FETCH_FAILED", exception.code());
        verify(client, times(3)).syncGitStore(any(), any());
        verify(client, never()).provisionWorkspace(any(), any());
    }
}
