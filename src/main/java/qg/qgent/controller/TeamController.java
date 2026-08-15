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
import qg.qgent.service.TeamService;

import java.util.Map;
import java.util.UUID;

/**
 * 团队与团队邀请接口
 * 列表/详情为团队成员可见，其余写操作需 Team Owner 权限。
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService teams;
    private final IdempotencyService idempotency;

    public TeamController(TeamService teams, IdempotencyService idempotency) {
        this.teams = teams;
        this.idempotency = idempotency;
    }

    /**
     * 契约 §5.1：创建团队，创建者成为 TEAM_OWNER。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamResponse> create(@AuthenticationPrincipal UUID actor,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @Valid @RequestBody CreateTeamRequest body, HttpServletRequest request) {
        TeamResponse result = idempotency.execute(actor, "POST:/teams", key, body, 201, TeamResponse.class,
                () -> teams.create(actor, body));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：分页获取我加入的团队。
     */
    @GetMapping
    public PagedApiResponse<TeamResponse> list(@AuthenticationPrincipal UUID actor,
                                               @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                               HttpServletRequest request) {
        return page(teams.list(actor, cursor, limit), request);
    }

    /**
     * 契约 §5.1：获取团队资料与当前用户生效角色。
     */
    @GetMapping("/{teamId}")
    public ApiResponse<TeamResponse> get(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                         HttpServletRequest request) {
        return ok(teams.get(actor, teamId), request);
    }

    /**
     * 契约 §5.1：Team Owner 修改团队名称（description 传 null 保留原值，传空串清空）。
     */
    @PatchMapping("/{teamId}")
    public ApiResponse<TeamResponse> update(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @Valid @RequestBody UpdateTeamRequest body, HttpServletRequest request) {
        TeamResponse result = idempotency.execute(actor, "PATCH:/teams/{teamId}", key,
                Map.of("teamId", teamId, "body", body), 200, TeamResponse.class,
                () -> teams.update(actor, teamId, body));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：Team Owner 解散团队：级联删除团队下所有项目、需求群、消息与成员关系（不可恢复）。
     * 仅 Team Owner 可调用，其他角色返回 403；同一 Idempotency-Key 重试幂等。
     */
    @DeleteMapping("/{teamId}")
    public ApiResponse<TeamResponse> disband(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamResponse result = idempotency.execute(actor, "DELETE:/teams/{teamId}", key,
                Map.of("teamId", teamId), 200, TeamResponse.class,
                () -> teams.disband(actor, teamId));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：分页获取团队成员及其生效角色。
     */
    @GetMapping("/{teamId}/members")
    public PagedApiResponse<TeamMemberResponse> members(@AuthenticationPrincipal UUID actor,
                                                        @PathVariable UUID teamId, @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(teams.members(actor, teamId, cursor, limit), request);
    }

    /**
     * 团队最近动态（团队成员可见，基于项目事件聚合，覆盖最近 24 小时）。
     * type 过滤逗号分隔、前缀匹配（如 TASK,MR）；limit 默认 20、最大 50。
     */
    @GetMapping("/{teamId}/activities")
    public PagedApiResponse<ActivityResponse> activities(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                                         @RequestParam(required = false) String type,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) Integer limit,
                                                         HttpServletRequest request) {
        return page(teams.activities(actor, teamId, type, cursor, limit), request);
    }

    /**
     * 契约 §5.1：Team Owner 按邮箱创建团队邀请。
     */
    @PostMapping("/{teamId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamInvitationResponse> invite(@AuthenticationPrincipal UUID actor, @PathVariable UUID teamId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @Valid @RequestBody InviteTeamMemberRequest body, HttpServletRequest request) {
        TeamInvitationResponse result = idempotency.execute(actor, "POST:/teams/{teamId}/invitations", key,
                Map.of("teamId", teamId, "body", body), 201, TeamInvitationResponse.class,
                () -> teams.invite(actor, teamId, body));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：分页查询团队邀请状态（Team Owner）。
     */
    @GetMapping("/{teamId}/invitations")
    public PagedApiResponse<TeamInvitationResponse> invitations(@AuthenticationPrincipal UUID actor,
                                                                @PathVariable UUID teamId, @RequestParam(required = false) String cursor,
                                                                @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return page(teams.invitations(actor, teamId, cursor, limit), request);
    }

    /**
     * 契约 §5.1：撤销未接受的团队邀请。
     */
    @DeleteMapping("/{teamId}/invitations/{invitationId}")
    public ApiResponse<TeamInvitationResponse> revoke(@AuthenticationPrincipal UUID actor,
                                                      @PathVariable UUID teamId, @PathVariable UUID invitationId,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamInvitationResponse result = idempotency.execute(actor,
                "DELETE:/teams/{teamId}/invitations/{invitationId}", key,
                Map.of("teamId", teamId, "invitationId", invitationId), 200, TeamInvitationResponse.class,
                () -> teams.revoke(actor, teamId, invitationId));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：调整团队成员角色（保护 canonical Owner）。
     */
    @PatchMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberResponse> updateMember(@AuthenticationPrincipal UUID actor,
                                                        @PathVariable UUID teamId, @PathVariable UUID userId,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @Valid @RequestBody UpdateTeamMemberRequest body, HttpServletRequest request) {
        TeamMemberResponse result = idempotency.execute(actor, "PATCH:/teams/{teamId}/members/{userId}", key,
                Map.of("teamId", teamId, "userId", userId, "body", body), 200, TeamMemberResponse.class,
                () -> teams.updateMember(actor, teamId, userId, body));
        return ok(result, request);
    }

    /**
     * 契约 §5.1：移除团队成员。
     */
    @DeleteMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberResponse> removeMember(@AuthenticationPrincipal UUID actor,
                                                        @PathVariable UUID teamId, @PathVariable UUID userId,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamMemberResponse result = idempotency.execute(actor, "DELETE:/teams/{teamId}/members/{userId}", key,
                Map.of("teamId", teamId, "userId", userId), 200, TeamMemberResponse.class,
                () -> teams.removeMember(actor, teamId, userId));
        return ok(result, request);
    }

    // 构建成功响应
    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    // 构建分页响应
    private <T> PagedApiResponse<T> page(PageSlice<T> value, HttpServletRequest request) {
        return new PagedApiResponse<>(value.getData(), value.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
