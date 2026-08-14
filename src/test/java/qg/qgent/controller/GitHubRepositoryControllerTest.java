package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import qg.qgent.api.ApiResponse;
import qg.qgent.dto.BindProjectRepositoryRequest;
import qg.qgent.dto.GitHubInstallationResponse;
import qg.qgent.dto.GitHubInstallationUrlResponse;
import qg.qgent.dto.GitHubRepositoryResponse;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.dto.UpdateProjectRepositoryRequest;
import qg.qgent.github.GitHubClient;
import qg.qgent.github.GitHubInstallationState;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.GitHubRepositoryService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubRepositoryControllerTest {

    @Mock
    private GitHubRepositoryService service;

    @Mock
    private CurrentActorProvider currentActor;

    private GitHubRepositoryController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new GitHubRepositoryController(service, currentActor, "https://frontend.com");
        request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getAttribute(any())).thenReturn("req-1");
    }

    @Test
    void createInstallationUrl() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        GitHubInstallationUrlResponse responseDto = new GitHubInstallationUrlResponse();
        responseDto.setInstallationUrl("https://github.com/apps/test-app/installations/new");
        when(service.createInstallationUrl(userId, teamId)).thenReturn(responseDto);

        ApiResponse<GitHubInstallationUrlResponse> response = controller.createInstallationUrl(teamId, request);

        assertNotNull(response);
        assertEquals("https://github.com/apps/test-app/installations/new", response.getData().getInstallationUrl());
    }

    @Test
    void listInstallations() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        GitHubInstallationResponse respDto = new GitHubInstallationResponse();
        respDto.setId(UUID.randomUUID());
        respDto.setAccountLogin("test-account");
        when(service.listInstallations(userId, teamId)).thenReturn(List.of(respDto));

        ApiResponse<List<GitHubInstallationResponse>> response = controller.listInstallations(teamId, request);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals("test-account", response.getData().get(0).getAccountLogin());
    }

    @Test
    void removeInstallation() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        ResponseEntity<Void> response = controller.removeInstallation(teamId, installationId);

        assertNotNull(response);
        assertEquals(204, response.getStatusCode().value());
        verify(service).removeInstallation(userId, teamId, installationId);
    }

    @Test
    void listTeamRepositories() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        GitHubRepositoryResponse repo = new GitHubRepositoryResponse();
        repo.setId(UUID.randomUUID());
        repo.setFullName("owner/repo");
        when(service.listTeamRepositories(userId, teamId)).thenReturn(List.of(repo));

        ApiResponse<List<GitHubRepositoryResponse>> response = controller.listTeamRepositories(teamId, request);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals("owner/repo", response.getData().get(0).getFullName());
    }

    @Test
    void bindProjectRepository() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        BindProjectRepositoryRequest req = new BindProjectRepositoryRequest();
        req.setInstallationId(UUID.randomUUID());
        req.setRepositoryId(UUID.randomUUID());
        req.setDefaultBranch("main");

        ProjectRepositoryResponse respDto = new ProjectRepositoryResponse();
        respDto.setId(UUID.randomUUID());
        respDto.setFullName("owner/repo");
        when(service.bindProjectRepository(userId, projectId, req)).thenReturn(respDto);

        ApiResponse<ProjectRepositoryResponse> response = controller.bindProjectRepository(projectId, req, request);

        assertNotNull(response);
        assertEquals("owner/repo", response.getData().getFullName());
    }

    @Test
    void listProjectRepositories() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        ProjectRepositoryResponse repo = new ProjectRepositoryResponse();
        repo.setId(UUID.randomUUID());
        repo.setFullName("owner/repo");
        when(service.listProjectRepositories(userId, projectId)).thenReturn(List.of(repo));

        ApiResponse<List<ProjectRepositoryResponse>> response = controller.listProjectRepositories(projectId, request);

        assertNotNull(response);
        assertEquals(1, response.getData().size());
        assertEquals("owner/repo", response.getData().get(0).getFullName());
    }

    @Test
    void updateProjectRepository() {
        UUID projectId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentActor.currentUserId()).thenReturn(userId);

        UpdateProjectRepositoryRequest req = new UpdateProjectRepositoryRequest();
        req.setDefaultBranch("develop");
        req.setDisplayName("My Repo");

        ProjectRepositoryResponse respDto = new ProjectRepositoryResponse();
        respDto.setId(repoId);
        when(service.updateProjectRepository(userId, projectId, repoId, req)).thenReturn(respDto);

        ApiResponse<ProjectRepositoryResponse> response = controller.updateProjectRepository(projectId, repoId, req, request);

        assertNotNull(response);
    }

    @Test
    void installationCallbackRedirectsToCorrectUrl() {
        long installationId = 12345L;
        String state = "test-state";
        UUID teamId = UUID.randomUUID();
        when(service.handleInstallationCallback(installationId, state)).thenReturn(teamId);

        org.springframework.http.ResponseEntity<Void> response = controller.installationCallback(installationId, state);

        assertEquals(302, response.getStatusCode().value());
        String expectedLocation = "https://frontend.com/app/integrations/github?teamId=" + teamId + "&installed=1";
        assertEquals(expectedLocation, response.getHeaders().getLocation().toString());
    }

    @Test
    void installationCallbackUsesMobileFrontendFromVerifiedState() {
        GitHubRepositoryController mobileController = new GitHubRepositoryController(
                service, currentActor, "https://web.example.com", "https://mobile.example.com");
        long installationId = 54321L;
        String state = "mobile-state";
        UUID teamId = UUID.randomUUID();
        when(service.handleInstallationCallbackDetails(installationId, state))
                .thenReturn(new GitHubInstallationState(teamId, GitHubClient.MOBILE));

        ResponseEntity<Void> response = mobileController.installationCallback(installationId, state, "update");

        assertEquals("https://mobile.example.com/app/integrations/github?teamId=" + teamId + "&installed=1",
                response.getHeaders().getLocation().toString());
    }
}
