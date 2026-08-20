package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitStoreSyncServiceTest {
    @Test
    void refreshesWorkerStoreAndReturnsTheCurrentRemoteCommit() {
        Fixture fixture = new Fixture();
        String sha = "a".repeat(40);
        when(fixture.github.getBranch(eq(fixture.installation.getProviderInstallationId()), eq("owner"), eq("repo"), eq("main")))
                .thenReturn(new GitHubBranchDetails("main", sha));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("grant");
        when(fixture.worker.syncGitStore(eq(fixture.repository.getId()), any()))
                .thenReturn(new WorkerGitStoreSyncResponse().setHeadCommit(sha));
        WorkerGitResolveResponse resolved = new WorkerGitResolveResponse();
        resolved.setCommitSha(sha);
        when(fixture.worker.resolveGitRef(any())).thenReturn(resolved);

        assertEquals(sha, fixture.service.refreshTargetBranch(fixture.projectId, fixture.repository, "main"));
        verify(fixture.worker).syncGitStore(eq(fixture.repository.getId()), any());
        verify(fixture.worker).resolveGitRef(any());
    }

    @Test
    void rejectsAWorkerStoreThatDidNotRefreshToRemoteHead() {
        Fixture fixture = new Fixture();
        String remote = "a".repeat(40);
        when(fixture.github.getBranch(anyLong(), any(), any(), any())).thenReturn(new GitHubBranchDetails("main", remote));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("grant");
        when(fixture.worker.syncGitStore(any(), any())).thenReturn(new WorkerGitStoreSyncResponse().setHeadCommit(remote));
        WorkerGitResolveResponse resolved = new WorkerGitResolveResponse();
        resolved.setCommitSha("b".repeat(40));
        when(fixture.worker.resolveGitRef(any())).thenReturn(resolved);

        ApiException error = assertThrows(ApiException.class,
                () -> fixture.service.refreshTargetBranch(fixture.projectId, fixture.repository, "main"));
        assertEquals("GIT_BASE_REF_NOT_SYNCED", error.code());
    }

    @Test
    void retriesTransientSyncWithFreshFetchGrant() {
        Fixture fixture = new Fixture();
        String sha = "a".repeat(40);
        when(fixture.github.getBranch(anyLong(), any(), any(), any())).thenReturn(new GitHubBranchDetails("main", sha));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("grant-first", "grant-second");
        when(fixture.worker.syncGitStore(eq(fixture.repository.getId()), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "GIT_REMOTE_NETWORK_FAILED", "transient"))
                .thenReturn(new WorkerGitStoreSyncResponse().setHeadCommit(sha));
        WorkerGitResolveResponse resolved = new WorkerGitResolveResponse();
        resolved.setCommitSha(sha);
        when(fixture.worker.resolveGitRef(any())).thenReturn(resolved);

        assertEquals(sha, fixture.service.refreshTargetBranch(fixture.projectId, fixture.repository, "main"));
        verify(fixture.github, org.mockito.Mockito.times(2)).getBranch(anyLong(), any(), any(), any());
        verify(fixture.credentials, org.mockito.Mockito.times(2)).generateGrant(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.worker, org.mockito.Mockito.times(2)).syncGitStore(eq(fixture.repository.getId()), any());
        verify(fixture.worker).resolveGitRef(any());
    }

    @Test
    void doesNotRetryUnclassifiedFetchFailure() {
        Fixture fixture = new Fixture();
        String sha = "a".repeat(40);
        when(fixture.github.getBranch(anyLong(), any(), any(), any())).thenReturn(new GitHubBranchDetails("main", sha));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("grant");
        when(fixture.worker.syncGitStore(eq(fixture.repository.getId()), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "GIT_STORE_FETCH_FAILED", "unclassified"));

        ApiException error = assertThrows(ApiException.class,
                () -> fixture.service.refreshTargetBranch(fixture.projectId, fixture.repository, "main"));

        assertEquals("GIT_STORE_FETCH_FAILED", error.code());
        verify(fixture.github).getBranch(anyLong(), any(), any(), any());
        verify(fixture.credentials).generateGrant(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.worker).syncGitStore(eq(fixture.repository.getId()), any());
    }

    @Test
    void refreshSourceHeadPersistsHeadWhenRemoteAdvanced() {
        Fixture fixture = new Fixture();
        String oldHead = "a".repeat(40);
        String remote = "b".repeat(40);
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feat/login"); worktree.setHeadCommit(oldHead);
        when(fixture.github.getBranch(eq(123L), eq("owner"), eq("repo"), eq("feat/login")))
                .thenReturn(new GitHubBranchDetails("feat/login", remote));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("grant");
        when(fixture.worker.syncGitStore(eq(fixture.repository.getId()), any()))
                .thenReturn(new WorkerGitStoreSyncResponse().setHeadCommit(remote));
        WorkerGitResolveResponse resolved = new WorkerGitResolveResponse();
        resolved.setCommitSha(remote);
        when(fixture.worker.resolveGitRef(any())).thenReturn(resolved);

        assertEquals(remote, fixture.service.refreshSourceHead(fixture.projectId, worktree, fixture.repository, workspaceId));
        verify(fixture.workspaces).updateHeadCommit(eq(workspaceId), eq(fixture.repository.getRepositoryId()), eq(remote));
        verify(fixture.worker).syncGitStore(eq(fixture.repository.getId()), any());
    }

    @Test
    void refreshSourceHeadIsNoopWhenRemoteMatchesLocalHead() {
        Fixture fixture = new Fixture();
        String head = "a".repeat(40);
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feat/login"); worktree.setHeadCommit(head);
        when(fixture.github.getBranch(eq(123L), eq("owner"), eq("repo"), eq("feat/login")))
                .thenReturn(new GitHubBranchDetails("feat/login", head));

        assertNull(fixture.service.refreshSourceHead(fixture.projectId, worktree, fixture.repository, workspaceId));
        verify(fixture.worker, never()).syncGitStore(any(), any());
        verify(fixture.workspaces, never()).updateHeadCommit(any(), any(), any());
    }

    @Test
    void refreshSourceHeadThrottlesRepeatedRefreshForSameHead() {
        Fixture fixture = new Fixture();
        String oldHead = "a".repeat(40);
        String remote = "b".repeat(40);
        UUID workspaceId = UUID.randomUUID();
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setSourceBranch("feat/login"); worktree.setHeadCommit(oldHead);
        when(fixture.github.getBranch(eq(123L), eq("owner"), eq("repo"), eq("feat/login")))
                .thenReturn(new GitHubBranchDetails("feat/login", remote));
        when(fixture.credentials.generateGrant(any(), any(), any(), any(), any(), any(), any())).thenReturn("grant");
        when(fixture.worker.syncGitStore(eq(fixture.repository.getId()), any()))
                .thenReturn(new WorkerGitStoreSyncResponse().setHeadCommit(remote));
        WorkerGitResolveResponse resolved = new WorkerGitResolveResponse();
        resolved.setCommitSha(remote);
        when(fixture.worker.resolveGitRef(any())).thenReturn(resolved);

        assertEquals(remote, fixture.service.refreshSourceHead(fixture.projectId, worktree, fixture.repository, workspaceId));
        // 同一 source 分支 + 同一旧 head 在节流窗口内再次刷新直接返回 null，不再打 Worker。
        assertNull(fixture.service.refreshSourceHead(fixture.projectId, worktree, fixture.repository, workspaceId));
        verify(fixture.worker, org.mockito.Mockito.times(1)).syncGitStore(any(), any());
    }

    private static final class Fixture {
        private final UUID projectId = UUID.randomUUID();
        private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
        private final GitHubInstallationMapper installations = mock(GitHubInstallationMapper.class);
        private final GitHubAppClient github = mock(GitHubAppClient.class);
        private final GitCredentialService credentials = mock(GitCredentialService.class);
        private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        private final WorkspaceRepositoryMapper workspaces = mock(WorkspaceRepositoryMapper.class);
        private final ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        private final GitHubInstallationEntity installation = new GitHubInstallationEntity();
        private final GitStoreSyncService service;

        private Fixture() {
            repository.setId(UUID.randomUUID()); repository.setProjectId(projectId); repository.setRepositoryId(UUID.randomUUID());
            GitHubRepositoryEntity githubRepository = new GitHubRepositoryEntity();
            githubRepository.setId(repository.getRepositoryId()); githubRepository.setInstallationId(UUID.randomUUID());
            githubRepository.setOwnerLogin("owner"); githubRepository.setName("repo");
            githubRepository.setAuthorizationStatus("AUTHORIZED"); githubRepository.setArchived(false);
            installation.setId(githubRepository.getInstallationId()); installation.setTeamId(UUID.randomUUID());
            installation.setProviderInstallationId(123L); installation.setStatus("ACTIVE");
            when(githubRepositories.selectById(repository.getRepositoryId())).thenReturn(githubRepository);
            when(installations.selectById(githubRepository.getInstallationId())).thenReturn(installation);
            service = new GitStoreSyncService(githubRepositories, installations, github, credentials, worker, workspaces);
        }
    }
}
