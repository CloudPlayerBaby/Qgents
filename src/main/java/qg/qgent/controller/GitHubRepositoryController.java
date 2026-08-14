package qg.qgent.controller;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
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
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
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

/**
 * GitHub App 团队授权与项目仓库绑定接口（§6）。
 * 调用者身份从安全上下文获取，团队/项目权限由服务端按资源归属校验。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "6 GitHub 集成与仓库", description = "团队 GitHub App 授权、仓库同步与项目仓库绑定")
public class GitHubRepositoryController {
    private final GitHubRepositoryService service;
    private final CurrentActorProvider currentActor;
    private final String frontendUrlWeb;
    private final String frontendUrlMobile;

    public GitHubRepositoryController(GitHubRepositoryService service, CurrentActorProvider currentActor,
            String frontendUrl) {
        this(service, currentActor, frontendUrl, frontendUrl);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubRepositoryController(GitHubRepositoryService service, CurrentActorProvider currentActor,
            @org.springframework.beans.factory.annotation.Value("${app.frontend-url-web:${app.frontend-url}}") String frontendUrlWeb,
            @org.springframework.beans.factory.annotation.Value("${app.frontend-url-mobile:${app.frontend-url}}") String frontendUrlMobile) {
        this.service = service;
        this.currentActor = currentActor;
        this.frontendUrlWeb = frontendUrlWeb;
        this.frontendUrlMobile = frontendUrlMobile;
    }

    /**
     * 生成 GitHub App 安装跳转地址（Team Owner）。
     */
    @PostMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<GitHubInstallationUrlResponse> createInstallationUrl(@PathVariable UUID teamId,
            @RequestParam(name = "client", defaultValue = "WEB") GitHubClient client,
            HttpServletRequest request) {
        return ok(service.createInstallationUrl(currentActor.currentUserId(), teamId, client), request);
    }

    /** Legacy overload used by existing callers; defaults to the Web client. */
    public ApiResponse<GitHubInstallationUrlResponse> createInstallationUrl(UUID teamId, HttpServletRequest request) {
        return ok(service.createInstallationUrl(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 查询团队已安装的 GitHub App 列表（Team Owner）。
     */
    @GetMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<List<GitHubInstallationResponse>> listInstallations(@PathVariable UUID teamId,
            HttpServletRequest request) {
        return ok(service.listInstallations(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 解除团队 GitHub App 安装记录（Team Owner）。
     */
    @DeleteMapping("/teams/{teamId}/integrations/github/installations/{installationId}")
    public ResponseEntity<Void> removeInstallation(@PathVariable UUID teamId, @PathVariable UUID installationId) {
        service.removeInstallation(currentActor.currentUserId(), teamId, installationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询团队被授权的仓库（Team Owner 或 Project Admin）。
     */
    @GetMapping("/teams/{teamId}/integrations/github/repositories")
    public ApiResponse<List<GitHubRepositoryResponse>> listTeamRepositories(@PathVariable UUID teamId,
            HttpServletRequest request) {
        return ok(service.listTeamRepositories(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 获取项目已绑定的仓库（项目成员）。
     */
    @GetMapping("/projects/{projectId}/repositories")
    public ApiResponse<List<ProjectRepositoryResponse>> listProjectRepositories(@PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(service.listProjectRepositories(currentActor.currentUserId(), projectId), request);
    }

    /**
     * 将团队已授权仓库绑定到项目（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/repositories")
    public ApiResponse<ProjectRepositoryResponse> bindProjectRepository(@PathVariable UUID projectId,
            @Valid @RequestBody BindProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.bindProjectRepository(currentActor.currentUserId(), projectId, body), request);
    }

    /**
     * 修改项目仓库绑定信息（Project Admin）。
     */
    @PatchMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ApiResponse<ProjectRepositoryResponse> updateProjectRepository(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId,
            @Valid @RequestBody UpdateProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.updateProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId, body), request);
    }

    /**
     * 解绑项目仓库（Project Admin）。
     */
    @DeleteMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ResponseEntity<Void> unbindProjectRepository(@PathVariable UUID projectId,
                                                          @PathVariable UUID projectRepositoryId) {
        service.unbindProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 接收 GitHub 安装/授权回调（无需 Qgents JWT）。
     */
    @Hidden
    public ResponseEntity<Void> installationCallback(long installationId, String state) {
        UUID teamId = service.handleInstallationCallback(installationId, state);
        return redirectTo(frontendUrlWeb, teamId);
    }

    @GetMapping("/integrations/github/callback")
    public ResponseEntity<Void> installationCallback(@RequestParam("installation_id") long installationId,
                                                      @RequestParam String state,
                                                      @RequestParam(name = "setup_action", required = false) String setupAction) {
        GitHubInstallationState callbackState = service.handleInstallationCallbackDetails(installationId, state);
        String frontendUrl = callbackState.client() == GitHubClient.MOBILE ? frontendUrlMobile : frontendUrlWeb;
        return redirectTo(frontendUrl, callbackState.teamId());
    }

    private ResponseEntity<Void> redirectTo(String frontendUrl, UUID teamId) {
        String redirectUrl = org.springframework.web.util.UriComponentsBuilder.fromUriString(frontendUrl)
                .pathSegment("app", "integrations", "github")
                .queryParam("teamId", teamId.toString())
                .queryParam("installed", "1")
                .build().toUriString();
        return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .header(org.springframework.http.HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    /**
     * 手动刷新 Installation 与授权仓库元数据（Team Owner）。
     */
    @PostMapping("/teams/{teamId}/integrations/github/installations/{installationId}/sync")
    public ApiResponse<GitHubInstallationResponse> manualSyncInstallation(@PathVariable UUID teamId,
            @PathVariable UUID installationId, HttpServletRequest request) {
        return ok(service.manualSyncInstallation(currentActor.currentUserId(), teamId, installationId), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
