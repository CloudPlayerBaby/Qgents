package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.TeamMemberResponse;
import qg.qgent.service.IdempotencyService;
import qg.qgent.service.TeamService;

import java.util.Map;
import java.util.UUID;

/**
 * 团队邀请接受端点（5.1）。
 * 用户持邀请令牌加入团队成为 TEAM_MEMBER；令牌与当前登录邮箱必须匹配，
 * 邀请已过期、已撤销或非待处理时返回 409。
 */
@RestController
@RequestMapping("/api/v1/team-invitations")
public class TeamInvitationController {
    private final TeamService teams;
    private final IdempotencyService idempotency;

    public TeamInvitationController(TeamService teams, IdempotencyService idempotency) {
        this.teams = teams;
        this.idempotency = idempotency;
    }

    /**
     * 接受邀请令牌并加入对应团队，返回当前用户在该团队中的成员信息。
     */
    @PostMapping("/{token}/accept")
    public ApiResponse<TeamMemberResponse> accept(@AuthenticationPrincipal UUID actor, @PathVariable String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamMemberResponse result = idempotency.execute(actor, "POST:/team-invitations/{token}/accept", key,
                Map.of("token", token), 200, TeamMemberResponse.class, () -> teams.accept(actor, token));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
