package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户收到的团队邀请（收件人视角）视图。
 * 明文邀请 token 不落库、也不对外返回（数据库仅存 SHA-256 哈希，安全底线），
 * 接受时使用本响应中的 {@code id} 调用 POST /api/v1/team-invitations/{id}/accept。
 * role 恒为 TEAM_MEMBER（邀请创建仅支持该角色，表中未持久化角色列）；
 * status 为 PENDING/EXPIRED（PENDING 但已过期的按 EXPIRED 展示）；
 * expiresAt/createdAt 为 ISO8601 UTC 时间字符串。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceivedInvitationResponse {
    private String id;
    private String teamId;
    private String teamName;
    private String role;
    private String inviterDisplayName;
    private String status;
    private String expiresAt;
    private String createdAt;
}
