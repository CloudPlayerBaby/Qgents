package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.ApiException;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.github.GitHubClient;
import qg.qgent.github.GitHubInstallationState;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.GitHubRepositoryService;

import java.util.List;
import java.util.UUID;

/**
 * GitHub App 团队授权与项目仓库绑定接口（契约 §6 GitHub App 与项目仓库）。
 * GitHub App 团队授权、仓库同步与项目仓库绑定；调用者身份从安全上下文获取，团队/项目权限由服务端按资源归属校验。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "GitHub 集成与仓库", description = "团队 GitHub App 授权、仓库同步与项目仓库绑定")
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
     * 契约 §6：生成 GitHub App 安装跳转地址（Team Owner）。
     */
    @PostMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<GitHubInstallationUrlResponse> createInstallationUrl(@PathVariable UUID teamId,
                                                                            @RequestParam(name = "client", defaultValue = "WEB") GitHubClient client,
                                                                            HttpServletRequest request) {
        return ok(service.createInstallationUrl(currentActor.currentUserId(), teamId, client), request);
    }

    /**
     * 遗留重载方法，供现有调用方复用，默认使用 Web 客户端。死代码，未路由，无接口编号。
     */
    public ApiResponse<GitHubInstallationUrlResponse> createInstallationUrl(UUID teamId, HttpServletRequest request) {
        return ok(service.createInstallationUrl(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 契约 §6：查询团队已安装的 GitHub App 列表（Team Owner）。
     */
    @GetMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<List<GitHubInstallationResponse>> listInstallations(@PathVariable UUID teamId,
                                                                           HttpServletRequest request) {
        return ok(service.listInstallations(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 契约 §6：解除团队 GitHub App 安装记录（Team Owner）。
     */
    @DeleteMapping("/teams/{teamId}/integrations/github/installations/{installationId}")
    public ResponseEntity<Void> removeInstallation(@PathVariable UUID teamId, @PathVariable UUID installationId) {
        service.removeInstallation(currentActor.currentUserId(), teamId, installationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 契约 §6：查询团队被授权的仓库（Team Owner 或 Project Admin）。
     */
    @GetMapping("/teams/{teamId}/integrations/github/repositories")
    public ApiResponse<List<GitHubRepositoryResponse>> listTeamRepositories(@PathVariable UUID teamId,
                                                                            HttpServletRequest request) {
        return ok(service.listTeamRepositories(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 契约 §6：获取项目已绑定的仓库（项目成员）。
     */
    @GetMapping("/projects/{projectId}/repositories")
    public ApiResponse<List<ProjectRepositoryResponse>> listProjectRepositories(@PathVariable UUID projectId,
                                                                                HttpServletRequest request) {
        return ok(service.listProjectRepositories(currentActor.currentUserId(), projectId), request);
    }

    /**
     * 契约 §6：查询项目绑定仓库的真实 GitHub 远程分支（项目成员）。
     */
    @GetMapping("/projects/{projectId}/repositories/{projectRepositoryId}/branches")
    public ApiResponse<List<RemoteBranchResponse>> listRemoteBranches(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId, HttpServletRequest request) {
        return ok(service.listRemoteBranches(currentActor.currentUserId(), projectId, projectRepositoryId), request);
    }

    /**
     * 契约 §6：从已有远程分支创建 GitHub 远程分支（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/repositories/{projectRepositoryId}/branches")
    public ApiResponse<RemoteBranchResponse> createRemoteBranch(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId,
            @Valid @RequestBody CreateRemoteBranchRequest body, HttpServletRequest request) {
        return ok(service.createRemoteBranch(currentActor.currentUserId(), projectId, projectRepositoryId, body), request);
    }

    /**
     * 契约 §6：将团队已授权仓库绑定到项目（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/repositories")
    public ApiResponse<ProjectRepositoryResponse> bindProjectRepository(@PathVariable UUID projectId,
                                                                        @Valid @RequestBody BindProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.bindProjectRepository(currentActor.currentUserId(), projectId, body), request);
    }

    /**
     * 项目级新建并绑定仓库。创建仓库属于团队外部资源操作，当前仅 Team Owner 可用；
     * 建仓固定带初始提交，避免把空仓库带入 Task/Workspace 链路。
     */
    @PostMapping("/projects/{projectId}/repositories/new")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectRepositoryResponse> createProjectRepository(
            @PathVariable UUID projectId, @Valid @RequestBody NewProjectRepositoryRequest body,
            HttpServletRequest request) {
        return ok(service.createProjectRepository(currentActor.currentUserId(), projectId, body), request);
    }

    /**
     * 契约 §6：修改项目仓库绑定信息（Project Admin）。
     */
    @PatchMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ApiResponse<ProjectRepositoryResponse> updateProjectRepository(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId,
            @Valid @RequestBody UpdateProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.updateProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId, body), request);
    }

    /**
     * 契约 §6：解绑项目仓库（Project Admin）。
     */
    @DeleteMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ResponseEntity<Void> unbindProjectRepository(@PathVariable UUID projectId,
                                                        @PathVariable UUID projectRepositoryId) {
        service.unbindProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 接收 GitHub 安装/授权回调（无需 Qgents JWT）。死代码，未路由，无接口编号；已被下方带 setup_action 参数的同名方法取代。
     */
    @Hidden
    public ResponseEntity<Void> installationCallback(long installationId, String state) {
        UUID teamId = service.handleInstallationCallback(installationId, state);
        return redirectTo(frontendUrlWeb, teamId);
    }

    /**
     * 契约 §6：接收 GitHub 安装/授权回调，按客户端类型重定向到对应前端（无需 Qgents JWT）。
     * 同一 GitHub 账号已绑定其他团队时，携带 conflict 参数重定向回前端展示明确提示，
     * 避免 409 被网关转成 502，用户看不到具体原因。
     */
    @GetMapping("/integrations/github/callback")
    public ResponseEntity<Void> installationCallback(@RequestParam("installation_id") long installationId,
                                                     @RequestParam(required = false) String state,
                                                     @RequestParam(name = "setup_action", required = false) String setupAction) {
        GitHubInstallationState callbackState;
        if (state != null && !state.isBlank()) {
            callbackState = service.handleInstallationCallbackDetails(installationId, state);
        } else if ("update".equalsIgnoreCase(setupAction)) {
            // GitHub Configure 页的更新回调不会回传最初安装时的 state；按已绑定 Installation 恢复团队。
            callbackState = service.handleInstallationUpdateCallback(installationId);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_CALLBACK_STATE_REQUIRED",
                    "GitHub 安装回调缺少 state");
        }
        String frontendUrl = callbackState.client() == GitHubClient.MOBILE ? frontendUrlMobile : frontendUrlWeb;
        if (callbackState.conflictCode() != null) {
            return redirectTo(frontendUrl, callbackState.teamId(), callbackState.conflictCode());
        }
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
     * 回调因安装归属冲突未完成同步：重定向回前端，带 conflict 错误码与原因说明。
     */
    private ResponseEntity<Void> redirectTo(String frontendUrl, UUID teamId, String conflictCode) {
        String message = "GITHUB_INSTALLATION_TEAM_CONFLICT".equals(conflictCode)
                ? "该 GitHub 账号已绑定到其他团队，一个账号只能授权给一个团队。如需更换，请先到原团队解绑或卸载 GitHub App 后重新安装。"
                : "GitHub 安装未完成，请稍后重试或联系管理员";
        String redirectUrl = org.springframework.web.util.UriComponentsBuilder.fromUriString(frontendUrl)
                .pathSegment("app", "integrations", "github")
                .queryParam("teamId", teamId.toString())
                .queryParam("installed", "0")
                .queryParam("conflict", conflictCode)
                .queryParam("message", org.springframework.web.util.UriUtils.encodeQueryParam(message,
                        java.nio.charset.StandardCharsets.UTF_8))
                .build().toUriString();
        return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .header(org.springframework.http.HttpHeaders.LOCATION, redirectUrl)
                .build();
    }

    /**
     * 契约 §6：手动刷新 Installation 与授权仓库元数据（Team Owner）。
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
