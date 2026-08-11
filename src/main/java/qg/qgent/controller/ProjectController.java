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
 * 项目与项目成员端点（5.2）。
 * 项目创建、资料修改、归档/恢复与成员管理；创建者自动成为 PROJECT_ADMIN，
 * canonical Team Owner 对本团队项目具有兜底管理权限，最后一名 Project Admin 始终受保护。
 * 所有写操作均支持 Idempotency-Key，授权由服务端依据认证身份与资源归属判断。
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
     * 创建项目并在同一事务中写入创建者 Admin 与初始成员。
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
     * 团队普通成员只看到已加入项目，canonical Team Owner 可看到团队全部项目。
     */
    @GetMapping("/teams/{teamId}/projects")
    public PagedApiResponse<ProjectResponse> list(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return page(projects.list(actor, teamId, cursor, limit), request);
    }

    /**
     * 获取当前调用者可见的活动项目。
     */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> get(@AuthenticationPrincipal UUID actor, @PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(projects.get(actor, projectId), request);
    }

    /**
     * 项目 Admin 或 canonical Team Owner 修改项目资料。
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
     * 归档项目；重复归档返回当前状态。
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
     * 恢复归档项目；授权检查允许读取 ARCHIVED 状态。
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
     * 分页获取项目成员与项目角色。
     */
    @GetMapping("/projects/{projectId}/members")
    public PagedApiResponse<ProjectMemberResponse> members(@AuthenticationPrincipal UUID actor,
            @PathVariable UUID projectId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(projects.members(actor, projectId, cursor, limit), request);
    }

    /**
     * 只能从所属团队的现有成员中添加，初始角色固定为 PROJECT_MEMBER。
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
     * 调整现有项目成员角色，并保护最后一名 Project Admin。
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
     * 移除项目成员；最后一名 Project Admin 始终受到保护。
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
