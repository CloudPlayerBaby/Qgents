package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AddProjectMemberRequest;
import qg.qgent.dto.CreateProjectRequest;
import qg.qgent.dto.PageSlice;
import qg.qgent.dto.ProjectMemberResponse;
import qg.qgent.dto.ProjectResponse;
import qg.qgent.dto.UpdateProjectMemberRequest;
import qg.qgent.dto.UpdateProjectRequest;
import qg.qgent.service.IdempotencyService;
import qg.qgent.service.ProjectService;

import java.util.Map;
import java.util.UUID;

/**
 * 项目与项目成员接口（§5.2）。
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
     * 创建项目，创建者自动成为 PROJECT_ADMIN。
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
     * 分页获取当前用户可见的项目。
     */
    @GetMapping("/teams/{teamId}/projects")
    public PagedApiResponse<ProjectResponse> list(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return page(projects.list(actor, teamId, cursor, limit), request);
    }

    /**
     * 获取项目资料。
     */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> get(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(projects.get(actor, projectId), request);
    }

    /**
     * 修改项目资料（Project Admin 或 Team Owner）。
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
     * 归档项目。
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
     * 恢复已归档项目。
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
     * 分页获取项目成员与角色。
     */
    @GetMapping("/projects/{projectId}/members")
    public PagedApiResponse<ProjectMemberResponse> members(@AuthenticationPrincipal UUID actor,
            @PathVariable UUID projectId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(projects.members(actor, projectId, cursor, limit), request);
    }

    /**
     * 将团队现有成员加入项目（初始 PROJECT_MEMBER）。
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
     * 调整项目成员角色（保护最后一名 Project Admin）。
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
     * 移除项目成员。
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
