package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.GroupCreateRequest;
import qg.qgent.dto.GroupUpdateRequest;
import qg.qgent.service.GroupService;

import java.util.Map;
import java.util.UUID;

/**
 * 项目群组接口（§7）。
 * POST 写操作的 Idempotency-Key 由 {@code IdempotencyFilter} 统一强制与回放。
 */
@RestController
@RequestMapping("/api/v1")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * 项目总群与需求群列表（按最近活跃排序）。
     */
    @GetMapping("/projects/{projectId}/groups")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            HttpServletRequest request) {
        return ok(groupService.list(userId, projectId), request);
    }

    /**
     * 创建 REQUIREMENT 需求群。
     */
    @PostMapping("/projects/{projectId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody GroupCreateRequest body, HttpServletRequest request) {
        return ok(groupService.create(userId, projectId, body), request);
    }

    /**
     * 获取群详情（含 memberCount）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.get(userId, projectId, groupId), request);
    }

    /**
     * 修改需求群标题、描述和关联仓库。
     */
    @PatchMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, @Valid @RequestBody GroupUpdateRequest body, HttpServletRequest request) {
        return ok(groupService.update(userId, projectId, groupId, body), request);
    }

    /**
     * 归档需求群（仅 REQUIREMENT）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.archive(userId, projectId, groupId), request);
    }

    /**
     * 获取群成员列表（含参与群聊的 Agent，无角色）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> members(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.members(userId, projectId, groupId), request);
    }

    /**
     * 当前用户退出群聊（即移出本项目成员）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/leave")
    public ApiResponse<?> leave(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, HttpServletRequest request) {
        groupService.leave(userId, projectId, groupId);
        return ok(Map.of(), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
