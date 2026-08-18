package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.dto.MergeRequestCommentRequest;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubPullRequestCommentDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MergeRequestCommentMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeRequestCommentServiceTest {
    @Mock private MergeRequestMapper mergeRequests;
    @Mock private MergeRequestCommentMapper comments;
    @Mock private ProjectRepositoryMapper projectRepositories;
    @Mock private GitHubRepositoryMapper githubRepositories;
    @Mock private GitHubInstallationMapper installations;
    @Mock private GitHubAppClient github;
    @Mock private ProjectAccessService access;
    @Mock private EventService events;

    private MergeRequestCommentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new MergeRequestCommentService(mergeRequests, comments, projectRepositories,
                githubRepositories, installations, github, access, events, null);
    }

    @Test
    void createsGitHubIssueCommentAndPersistsItsMirror() {
        UUID projectId = UUID.randomUUID();
        UUID mrId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID projectRepositoryId = UUID.randomUUID();
        UUID githubRepositoryId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();

        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(mrId);
        mr.setProjectRepositoryId(projectRepositoryId);
        mr.setProviderNumber(42L);
        mr.setStatus("OPEN");
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        binding.setRepositoryId(githubRepositoryId);
        GitHubRepositoryEntity repository = new GitHubRepositoryEntity();
        repository.setId(githubRepositoryId);
        repository.setInstallationId(installationId);
        repository.setOwnerLogin("owner");
        repository.setName("repo");
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setProviderInstallationId(123L);

        when(mergeRequests.selectById(mrId)).thenReturn(mr);
        when(projectRepositories.selectById(projectRepositoryId)).thenReturn(binding);
        when(githubRepositories.selectById(githubRepositoryId)).thenReturn(repository);
        when(installations.selectById(installationId)).thenReturn(installation);
        when(github.createPullRequestComment(123L, "owner", "repo", 42,
                new qg.qgent.github.GitHubPullRequestCommentRequest("hello")))
                .thenReturn(new GitHubPullRequestCommentDetails(3001L, "hello",
                        "https://github.com/owner/repo/pull/42#issuecomment-3001", "2026-08-18T15:00:00Z"));

        MergeRequestCommentRequest request = new MergeRequestCommentRequest();
        request.setBody(" hello ");
        var response = service.add(projectId, mrId, actor, request);

        assertEquals("hello", response.getBody());
        assertEquals("3001", response.getProviderCommentId());
        verify(comments).insert(any(qg.qgent.entity.MergeRequestCommentEntity.class));
        verify(events).publish(any(), any(), any(), any(), any());
    }
}
