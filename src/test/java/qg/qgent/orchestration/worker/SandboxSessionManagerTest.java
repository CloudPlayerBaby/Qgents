package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        
        qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse syncResponse = new qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse();
        when(client.syncGitStore(any(), any())).thenReturn(syncResponse);
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
}
