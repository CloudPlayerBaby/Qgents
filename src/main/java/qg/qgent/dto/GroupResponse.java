package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(description = "群 ID")
    private String id;

    /** 所属项目 ID。 */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /** 群类型：PROJECT_MAIN / REQUIREMENT。 */
    @Schema(description = "群类型：PROJECT_MAIN / REQUIREMENT")
    private String type;

    /** 群标题。 */
    @Schema(description = "群标题")
    private String title;

    /** 群说明。 */
    @Schema(description = "群说明")
    private String description;

    /** 群状态：ACTIVE / ARCHIVED。 */
    @Schema(description = "群状态：ACTIVE / ARCHIVED")
    private String status;

    /** 最近消息时间（UTC），从未发言为空。 */
    @Schema(description = "最近消息时间（UTC），从未发言为空")
    private LocalDateTime lastMessageAt;

    /** 最近活跃时间（UTC），排序依据；从未发言时以创建时间兜底（A 联调约定 §2）。 */
    @Schema(description = "最近活跃时间（UTC），排序依据；从未发言时以创建时间兜底")
    private LocalDateTime latestActivityAt;

    /** 最新消息摘要（发送者昵称 + 文本），从未发言为空（A 联调约定 §2）。 */
    @Schema(description = "最新消息摘要（发送者昵称 + 文本），从未发言为空")
    private GroupLatestMessage latestMessage;

    /** 创建时间（UTC）。 */
    @Schema(description = "创建时间（UTC）")
    private LocalDateTime createdAt;

    /** 关联的项目仓库绑定 ID 列表。 */
    @Schema(description = "关联的项目仓库绑定 ID 列表")
    private List<String> repositoryIds;

    /** 群成员数（= 项目成员数）。 */
    @Schema(description = "群成员数（= 项目成员数）")
    private Long memberCount;
}
