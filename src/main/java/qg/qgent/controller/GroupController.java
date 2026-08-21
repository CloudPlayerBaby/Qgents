package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.GroupCreateRequest;
import qg.qgent.dto.GroupMemberAddRequest;
import qg.qgent.dto.GroupUpdateRequest;
import qg.qgent.dto.PinGroupRequest;
import qg.qgent.service.GroupService;

import java.util.Map;
import java.util.UUID;

/**
 * 项目群组接口
 * 提供项目群组列表查询、创建、详情、更新、归档、成员列表与退出操作。
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
     * 契约 §7：项目总群与需求群列表（按最近活跃排序）。
     */
    @GetMapping("/projects/{projectId}/groups")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                               HttpServletRequest request) {
        return ok(groupService.list(userId, projectId), request);
    }

    /**
     * 契约 §7 补充：群聊工作台聚合——一次返回当前用户所有可见项目的主群（消除三层 N+1）。
     */
    @GetMapping("/chat/main-groups")
    public ApiResponse<?> mainGroups(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(groupService.mainGroups(userId), request);
    }

    /**
     * 契约 §7：创建 REQUIREMENT 需求群。
     */
    @PostMapping("/projects/{projectId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                 @Valid @RequestBody GroupCreateRequest body, HttpServletRequest request) {
        return ok(groupService.create(userId, projectId, body), request);
    }

    /**
     * 契约 §7：获取群详情（含 memberCount）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                              @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.get(userId, projectId, groupId), request);
    }

    /**
     * 契约 §7：修改需求群标题、描述和关联仓库。
     */
    @PatchMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                 @PathVariable UUID groupId, @Valid @RequestBody GroupUpdateRequest body, HttpServletRequest request) {
        return ok(groupService.update(userId, projectId, groupId, body), request);
    }

    /**
     * 契约 §7：归档需求群（仅 REQUIREMENT）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                  @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.archive(userId, projectId, groupId), request);
    }

    /**
     * 契约 §7：获取群成员列表（含参与群聊的 Agent，无角色）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> members(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                  @PathVariable UUID groupId, HttpServletRequest request) {
        return ok(groupService.members(userId, projectId, groupId), request);
    }

    /**
     * 契约 2026-08-17：邀请项目成员入群（群创建者或 Project Admin；主群 422）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> addMember(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                    @PathVariable UUID groupId, @Valid @RequestBody GroupMemberAddRequest body,
                                    HttpServletRequest request) {
        return ok(groupService.addMember(userId, projectId, groupId, body.getUserId()), request);
    }

    /**
     * 契约 2026-08-17：移出群聊（群创建者或 Project Admin；创建者本人不可移出；主群 422）。
     */
    @DeleteMapping("/projects/{projectId}/groups/{groupId}/members/{memberUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                             @PathVariable UUID groupId, @PathVariable UUID memberUserId,
                             HttpServletRequest request) {
        groupService.removeMember(userId, projectId, groupId, memberUserId);
    }

    /**
     * 契约 §7：当前用户退出群聊（即移出本项目成员）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/leave")
    public ApiResponse<?> leave(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                @PathVariable UUID groupId, HttpServletRequest request) {
        groupService.leave(userId, projectId, groupId);
        return ok(Map.of(), request);
    }

    /**
     * 契约 §7 未读权威化补充：标记已读（进群全读语义）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/read")
    public ApiResponse<?> markRead(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                   @PathVariable UUID groupId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   HttpServletRequest request) {
        return ok(groupService.markRead(userId, projectId, groupId), request);
    }

    /**
     * 群聊置顶（个人偏好持久化）：设置 / 取消当前用户对某群的置顶，仅影响自己。
     * 幂等由 IdempotencyFilter 强制 Idempotency-Key 保证，重复设置相同值返回 200。
     */
    @PutMapping("/projects/{projectId}/groups/{groupId}/pin")
    public ApiResponse<?> setPinned(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                    @PathVariable UUID groupId, @Valid @RequestBody PinGroupRequest body,
                                    HttpServletRequest request) {
        return ok(groupService.setPinned(userId, projectId, groupId, body.getPinned()), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
