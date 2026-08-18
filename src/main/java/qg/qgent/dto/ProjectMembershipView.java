package qg.qgent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ProjectMembershipView {
    private UUID id;
    private UUID teamId;
    private String name;
    private String description;
    private String role;
    private String status;
    /**
     * 项目最后活跃时间（该群最后消息时间或创建时间的最大值），仅按活跃排序查询返回。
     */
    private LocalDateTime lastActivityAt;
}
