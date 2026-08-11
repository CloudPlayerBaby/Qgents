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

@RestController
@RequestMapping("/api/v1/team-invitations")
public class TeamInvitationController {
    private final TeamService teams;
    private final IdempotencyService idempotency;

    public TeamInvitationController(TeamService teams, IdempotencyService idempotency) {
        this.teams = teams;
        this.idempotency = idempotency;
    }

    @PostMapping("/{token}/accept")
    public ApiResponse<TeamMemberResponse> accept(@AuthenticationPrincipal UUID actor, @PathVariable String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamMemberResponse result = idempotency.execute(actor, "POST:/team-invitations/{token}/accept", key,
                Map.of("token", token), 200, TeamMemberResponse.class, () -> teams.accept(actor, token));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
