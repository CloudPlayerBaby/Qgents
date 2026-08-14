package qg.qgent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class TeamMembershipView {
    private UUID id;
    private UUID ownerUserId;
    private String name;
    private String role;
    /** 团队成员数（列表查询聚合）。 */
    private Integer memberCount;
    private String description;
    private LocalDateTime createdAt;
}
