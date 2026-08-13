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

import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.BindProjectRepositoryRequest;
import qg.qgent.dto.GitHubInstallationResponse;
import qg.qgent.dto.GitHubInstallationUrlResponse;
import qg.qgent.dto.GitHubRepositoryResponse;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.dto.UpdateProjectRepositoryRequest;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.GitHubRepositoryService;

/**
 * 6.GitHub App 团队授权与项目仓库绑定接口。除 GitHub 回调外，调用者身份均从安全上下文获取，
 * 具体团队和项目权限由服务层根据资源归属校验。
 */
@RestController
@RequestMapping("/api/v1")
public class GitHubRepositoryController {
    private final GitHubRepositoryService service;
    private final CurrentActorProvider currentActor;
    private final String frontendUrl;

    /**
     * 创建 GitHub 接口控制器。
     *
     * @param service GitHub 授权和仓库绑定业务服务
     * @param currentActor 当前认证用户提供者
     */
    public GitHubRepositoryController(GitHubRepositoryService service, CurrentActorProvider currentActor,
            @org.springframework.beans.factory.annotation.Value("${app.frontend-url}") String frontendUrl) {
        this.service = service;
        this.currentActor = currentActor;
        this.frontendUrl = frontendUrl;
    }

    /**
     * 为团队所有者生成 GitHub App 安装跳转地址。
     *
     * @param teamId 接收 GitHub App 授权的团队 ID
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 含短时有效安装地址和请求追踪 ID 的统一响应
     */
    @PostMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<GitHubInstallationUrlResponse> createInstallationUrl(@PathVariable UUID teamId,
            HttpServletRequest request) {
        return ok(service.createInstallationUrl(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 查询团队已保存的 GitHub App 安装记录，仅 Team Owner 可访问。
     *
     * @param teamId 团队 ID
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 团队安装记录列表的统一响应
     */
    @GetMapping("/teams/{teamId}/integrations/github/installations")
    public ApiResponse<List<GitHubInstallationResponse>> listInstallations(@PathVariable UUID teamId,
            HttpServletRequest request) {
        return ok(service.listInstallations(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 解除团队的 GitHub App 安装记录，仅 Team Owner 可执行。
     * 当仓库仍被项目绑定时，服务层拒绝删除以保持项目资源引用完整。
     *
     * @param teamId 团队 ID
     * @param installationId Qgents 安装记录 ID
     * @return 删除成功时返回 204
     */
    @DeleteMapping("/teams/{teamId}/integrations/github/installations/{installationId}")
    public ResponseEntity<Void> removeInstallation(@PathVariable UUID teamId, @PathVariable UUID installationId) {
        service.removeInstallation(currentActor.currentUserId(), teamId, installationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询团队 GitHub App 已授权的仓库，Team Owner 或该团队项目的 Project Admin 可访问。
     *
     * @param teamId 团队 ID
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 已授权仓库列表的统一响应
     */
    @GetMapping("/teams/{teamId}/integrations/github/repositories")
    public ApiResponse<List<GitHubRepositoryResponse>> listTeamRepositories(@PathVariable UUID teamId,
            HttpServletRequest request) {
        return ok(service.listTeamRepositories(currentActor.currentUserId(), teamId), request);
    }

    /**
     * 查询项目已绑定的 GitHub 仓库，项目成员可访问。
     *
     * @param projectId 项目 ID
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 项目仓库绑定列表的统一响应
     */
    @GetMapping("/projects/{projectId}/repositories")
    public ApiResponse<List<ProjectRepositoryResponse>> listProjectRepositories(@PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(service.listProjectRepositories(currentActor.currentUserId(), projectId), request);
    }

    /**
     * 将团队已授权安装中的 GitHub 仓库绑定到项目，仅 Project Admin 可执行。
     * 服务层验证 installationId、repositoryId 与项目所属团队的授权关系。
     *
     * @param projectId 项目 ID
     * @param body 绑定请求，包含安装记录、仓库及可选显示信息
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 新建项目仓库绑定的统一响应
     */
    @PostMapping("/projects/{projectId}/repositories")
    public ApiResponse<ProjectRepositoryResponse> bindProjectRepository(@PathVariable UUID projectId,
            @Valid @RequestBody BindProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.bindProjectRepository(currentActor.currentUserId(), projectId, body), request);
    }

    /**
     * 更新项目仓库绑定的默认分支或显示名称，仅 Project Admin 可执行。
     *
     * @param projectId 项目 ID
     * @param projectRepositoryId 项目仓库绑定 ID
     * @param body 更新请求
     * @param request 当前 HTTP 请求，用于返回请求追踪 ID
     * @return 更新后项目仓库绑定的统一响应
     */
    @PatchMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ApiResponse<ProjectRepositoryResponse> updateProjectRepository(
            @PathVariable UUID projectId, @PathVariable UUID projectRepositoryId,
            @Valid @RequestBody UpdateProjectRepositoryRequest body, HttpServletRequest request) {
        return ok(service.updateProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId, body), request);
    }

    /**
     * 解绑项目仓库，仅 Project Admin 可执行。
     * 服务层会拒绝解绑仍被分支策略引用的仓库。
     *
     * @param projectId 项目 ID
     * @param projectRepositoryId 项目仓库绑定 ID
     * @return 删除成功时返回 204
     */
    @DeleteMapping("/projects/{projectId}/repositories/{projectRepositoryId}")
    public ResponseEntity<Void> unbindProjectRepository(@PathVariable UUID projectId,
                                                          @PathVariable UUID projectRepositoryId) {
        service.unbindProjectRepository(currentActor.currentUserId(), projectId, projectRepositoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 接收 GitHub 安装或授权回调。该端点不要求 Qgents JWT，服务层仅接受短时有效的签名 state。
     *
     * @param installationId GitHub 提供的安装数字 ID
     * @param state 创建安装跳转地址时生成的签名状态
     * @return 同步完成时返回 204
     */
    @GetMapping("/integrations/github/callback")
    public ResponseEntity<Void> installationCallback(@RequestParam("installation_id") long installationId,
                                                      @RequestParam String state) {
        UUID teamId = service.handleInstallationCallback(installationId, state);
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
     * 手动触发指定授权的全量同步。
     * 只有 Team Owner 才能执行此操作。
     *
     * @param teamId 团队 ID
     * @param installationId Qgents 安装记录 ID
     * @param request 当前 HTTP 请求
     * @return 同步完成的安装记录响应
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
