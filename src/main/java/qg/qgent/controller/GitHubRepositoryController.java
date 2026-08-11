package qg.qgent.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.common.ApiResponse;
import qg.qgent.dto.GitHubRepositoryDtos;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.GitHubRepositoryService;

@RestController
@RequestMapping("/api/v1")
public class GitHubRepositoryController {
    private final GitHubRepositoryService service;
    private final CurrentActorProvider currentActor;

    public GitHubRepositoryController(GitHubRepositoryService service, CurrentActorProvider currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @PostMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<GitHubRepositoryDtos.InstallationUrlResponse> createInstallationUrl(@PathVariable UUID teamId) {
        return ApiResponse.of(service.createInstallationUrl(currentActor.currentUserId(), teamId));
    }

    @GetMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<List<GitHubRepositoryDtos.InstallationResponse>> listInstallations(@PathVariable UUID teamId) {
        return ApiResponse.of(service.listInstallations(currentActor.currentUserId(), teamId));
    }

    @DeleteMapping("/teams/{teamId}/integrations/github/installations/{installationId}")
    public ResponseEntity<Void> removeInstallation(@PathVariable UUID teamId, @PathVariable UUID installationId) {
        service.removeInstallation(currentActor.currentUserId(), teamId, installationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teams/{teamId}/integrations/github/repositories")
    public ApiResponse<List<GitHubRepositoryDtos.RepositoryResponse>> listTeamRepositories(@PathVariable UUID teamId) {
        return ApiResponse.of(service.listTeamRepositories(currentActor.currentUserId(), teamId));
    }

    @GetMapping("/projects/{projectId}/repositories")
    public ApiResponse<List<GitHubRepositoryDtos.ProjectRepositoryResponse>> listProjectRepositories(
            @PathVariable UUID projectId) {
        return ApiResponse.of(service.listProjectRepositories(currentActor.currentUserId(), projectId));
    }

    @PostMapping("/projects/{projectId}/repositories")
    public ApiResponse<GitHubRepositoryDtos.ProjectRepositoryResponse> bindProjectRepository(
            @PathVariable UUID projectId, @Valid @RequestBody GitHubRepositoryDtos.BindProjectRepositoryRequest request) {
        return ApiResponse.of(service.bindProjectRepository(currentActor.currentUserId(), projectId, request));
    }

    @PatchMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ApiResponse<GitHubRepositoryDtos.ProjectRepositoryResponse> updateProjectRepository(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId,
            @Valid @RequestBody GitHubRepositoryDtos.UpdateProjectRepositoryRequest request) {
        return ApiResponse.of(service.updateProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId, request));
    }

    @DeleteMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ResponseEntity<Void> unbindProjectRepository(@PathVariable UUID projectId,
                                                          @PathVariable UUID projectRepositoryId) {
        service.unbindProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/integrations/github/callback")
    public ResponseEntity<Void> installationCallback(@RequestParam("installation_id") long installationId,
                                                      @RequestParam String state) {
        service.handleInstallationCallback(installationId, state);
        return ResponseEntity.noContent().build();
    }
}
