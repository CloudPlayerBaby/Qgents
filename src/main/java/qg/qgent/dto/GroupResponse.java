package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求群视图（契约 §7 统一 Group）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {

    /** 群 ID。 */
    private String id;

    /** 所属项目 ID。 */
    private String projectId;

    /** 群类型：PROJECT_MAIN / REQUIREMENT。 */
    private String type;

    /** 群标题。 */
    private String title;

    /** 群说明。 */
    private String description;

    /** 群状态：ACTIVE / ARCHIVED。 */
    private String status;

    /** 最近消息时间（UTC），从未发言为空。 */
    private LocalDateTime lastMessageAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 关联的项目仓库绑定 ID 列表。 */
    private List<String> repositoryIds;

    /** 群成员数（= 项目成员数）。 */
    private Long memberCount;
}
