package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.IdempotencyService;
import qg.qgent.service.ProjectService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目与项目成员接口
 * 项目资料的创建、查询、修改、归档/恢复与项目成员管理。
 */
@RestController
@RequestMapping("/api/v1")
public class ProjectController {
    private final ProjectService projects;
    private final IdempotencyService idempotency;

    public ProjectController(ProjectService projects, IdempotencyService idempotency) {
        this.projects = projects;
        this.idempotency = idempotency;
    }

    /**
     * 契约 §5.2：创建项目，创建者自动成为 PROJECT_ADMIN。
     */
    @PostMapping("/teams/{teamId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody CreateProjectRequest body, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/teams/{teamId}/projects", key,
                Map.of("teamId", teamId, "body", body), 201, ProjectResponse.class,
                () -> projects.create(actor, teamId, body));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：分页获取当前用户可见的项目。
     */
    @GetMapping("/teams/{teamId}/projects")
    public PagedApiResponse<ProjectResponse> list(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                                  @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return page(projects.list(actor, teamId, cursor, limit), request);
    }

    /**
     * 契约 v2.0.6 补充：获取某团队下当前用户可见的项目，按最后活跃时间倒序（项目最后活跃 =
     * 其下所有群最近消息/创建时间的最大值，无群时以项目创建时间兜底）。不分页，供项目选择/工作台
     * 按最近活跃展示。
     */
    @GetMapping("/teams/{teamId}/projects/by-last-activity")
    public ApiResponse<List<ProjectResponse>> listByLastActivity(@AuthenticationPrincipal UUID actor,
                                                                 @PathVariable UUID teamId,
                                                                 HttpServletRequest request) {
        return ok(projects.listByLastActivity(actor, teamId), request);
    }

    /**
     * 契约 §5.2：获取项目资料。
     */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> get(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                            HttpServletRequest request) {
        return ok(projects.get(actor, projectId), request);
    }

    /**
     * 契约 §5.2：修改项目资料（Project Admin 或 Team Owner）。
     */
    @PatchMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> update(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody UpdateProjectRequest body, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "PATCH:/projects/{projectId}", key,
                Map.of("projectId", projectId, "body", body), 200, ProjectResponse.class,
                () -> projects.update(actor, projectId, body));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：归档项目。
     */
    @PostMapping("/projects/{projectId}/archive")
    public ApiResponse<ProjectResponse> archive(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/archive", key,
                Map.of("projectId", projectId), 200, ProjectResponse.class,
                () -> projects.archive(actor, projectId));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：恢复已归档项目。
     */
    @PostMapping("/projects/{projectId}/restore")
    public ApiResponse<ProjectResponse> restore(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/restore", key,
                Map.of("projectId", projectId), 200, ProjectResponse.class,
                () -> projects.restore(actor, projectId));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：分页获取项目成员与角色。
     */
    @GetMapping("/projects/{projectId}/members")
    public PagedApiResponse<ProjectMemberResponse> members(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @RequestParam(required = false) String cursor,
                                                           @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(projects.members(actor, projectId, cursor, limit), request);
    }

    /**
     * 契约 §5.2：将团队现有成员加入项目（初始 PROJECT_MEMBER）。
     */
    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectMemberResponse> addMember(@AuthenticationPrincipal UUID actor,
                                                        @PathVariable UUID projectId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @Valid @RequestBody AddProjectMemberRequest body, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor, "POST:/projects/{projectId}/members", key,
                Map.of("projectId", projectId, "body", body), 201, ProjectMemberResponse.class,
                () -> projects.addMember(actor, projectId, body));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：调整项目成员角色（保护最后一名 Project Admin）。
     */
    @PatchMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> updateMember(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @PathVariable UUID userId,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           @Valid @RequestBody UpdateProjectMemberRequest body, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor,
                "PATCH:/projects/{projectId}/members/{userId}", key,
                Map.of("projectId", projectId, "userId", userId, "body", body), 200,
                ProjectMemberResponse.class, () -> projects.updateMember(actor, projectId, userId, body));
        return ok(value, request);
    }

    /**
     * 契约 §5.2：移除项目成员。
     */
    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> removeMember(@AuthenticationPrincipal UUID actor,
                                                           @PathVariable UUID projectId, @PathVariable UUID userId,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        ProjectMemberResponse value = idempotency.execute(actor,
                "DELETE:/projects/{projectId}/members/{userId}", key,
                Map.of("projectId", projectId, "userId", userId), 200, ProjectMemberResponse.class,
                () -> projects.removeMember(actor, projectId, userId));
        return ok(value, request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private <T> PagedApiResponse<T> page(PageSlice<T> value, HttpServletRequest request) {
        return new PagedApiResponse<>(value.getData(), value.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
