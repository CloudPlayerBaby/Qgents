package qg.qgent.dto;

import lombok.Data;

import java.util.UUID;

/**
 * 团队成员列表查询的行视图：join users 表补充显示名与邮箱，
 * 供 {@link qg.qgent.service.TeamService#members} 构造 {@link TeamMemberResponse}。
 */
@Data
public class TeamMemberView {
    private UUID teamId;
    private UUID userId;
    private String role;
    /**
     * 成员用户显示名（users.display_name）。
     */
    private String displayName;
    /**
     * 成员用户邮箱（users.email）。
     */
    private String email;
}
