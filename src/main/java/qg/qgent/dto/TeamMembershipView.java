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
    /**
     * 团队成员数（列表查询聚合）。
     */
    private Integer memberCount;
    private String description;
    private LocalDateTime createdAt;
    /**
     * 团队头像 URL（可为空）。
     */
    private String avatarUrl;
    /**
     * 团队最后活跃时间（该团队下所有项目最后活跃的最大值），仅按活跃排序查询返回。
     */
    private LocalDateTime lastActivityAt;
}
