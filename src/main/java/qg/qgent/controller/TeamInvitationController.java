package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.PageSlice;
import qg.qgent.dto.ReceivedInvitationResponse;
import qg.qgent.dto.TeamMemberResponse;
import qg.qgent.service.IdempotencyService;
import qg.qgent.service.TeamService;

import java.util.Map;
import java.util.UUID;

/**
 * 团队邀请接口（收件人视角）
 * 提供「当前用户收到的待处理邀请列表」与「接受邀请（按邀请 id 或明文 token）」。
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
     * 当前用户收到的待处理团队邀请列表（PENDING，过期按 EXPIRED 展示）。
     * 不返回明文邀请 token（数据库仅存 SHA-256 哈希），接受时使用响应中的 id。
     */
    @GetMapping
    public PagedApiResponse<ReceivedInvitationResponse> myInvitations(@AuthenticationPrincipal UUID actor,
                                                                      @RequestParam(required = false) String cursor,
                                                                      @RequestParam(required = false) Integer limit,
                                                                      HttpServletRequest request) {
        PageSlice<ReceivedInvitationResponse> slice = teams.myInvitations(actor, cursor, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 接受团队邀请并加入团队。
     * reference 为「邀请记录 id（UUIDv7）或明文邀请 token」，二者以 UUID 解析区分：
     * 明文 token 由 base64url 生成、无连字符，不会被误判为 id；按 id 接受同样校验
     * 当前用户邮箱与被邀请邮箱一致。携带 Idempotency-Key 幂等重试。
     */
    @PostMapping("/{reference}/accept")
    public ApiResponse<TeamMemberResponse> accept(@AuthenticationPrincipal UUID actor, @PathVariable String reference,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        TeamMemberResponse result = idempotency.execute(actor, "POST:/team-invitations/{token}/accept", key,
                Map.of("reference", reference), 200, TeamMemberResponse.class, () -> teams.accept(actor, reference));
        return ApiResponse.ok(result, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
