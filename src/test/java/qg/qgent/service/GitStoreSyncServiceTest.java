package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private static final class Fixture {
        private final UUID projectId = UUID.randomUUID();
        private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
        private final GitHubInstallationMapper installations = mock(GitHubInstallationMapper.class);
        private final GitHubAppClient github = mock(GitHubAppClient.class);
        private final GitCredentialService credentials = mock(GitCredentialService.class);
        private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
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
            service = new GitStoreSyncService(githubRepositories, installations, github, credentials, worker);
        }
    }
}
