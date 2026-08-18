package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 需求群视图（契约 §7 统一 Group）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {

    /**
     * 群 ID。
     */
    @Schema(description = "群 ID")
    private String id;

    /**
     * 所属项目 ID。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * 群类型：PROJECT_MAIN / REQUIREMENT。
     */
    @Schema(description = "群类型：PROJECT_MAIN / REQUIREMENT")
    private String type;

    /**
     * 群标题。
     */
    @Schema(description = "群标题")
    private String title;

    /**
     * 群说明。
     */
    @Schema(description = "群说明")
    private String description;

    /**
     * 群状态：ACTIVE / ARCHIVED。
     */
    @Schema(description = "群状态：ACTIVE / ARCHIVED")
    private String status;

    /**
     * 群创建者用户 ID（需求群创建者，用于前端判断「归档需求群」按钮显隐）。
     */
    @Schema(description = "群创建者用户 ID")
    private String createdBy;

    /**
     * 最近消息时间（ISO8601 UTC，带时区后缀 Z），从未发言为空。
     */
    @Schema(description = "最近消息时间（ISO8601 UTC）")
    private String lastMessageAt;

    /**
     * 最近活跃时间（ISO8601 UTC，带时区后缀 Z），排序依据；从未发言时以创建时间兜底（A 联调约定 §2）。
     */
    @Schema(description = "最近活跃时间（ISO8601 UTC）")
    private String latestActivityAt;

    /**
     * 最新消息摘要（发送者昵称 + 文本），从未发言为空（A 联调约定 §2）。
     */
    @Schema(description = "最新消息摘要（发送者昵称 + 文本），从未发言为空")
    private GroupLatestMessage latestMessage;

    /**
     * 创建时间（ISO8601 UTC，带时区后缀 Z）。
     */
    @Schema(description = "创建时间（ISO8601 UTC）")
    private String createdAt;

    /**
     * 关联的项目仓库绑定 ID 列表。
     */
    @Schema(description = "关联的项目仓库绑定 ID 列表")
    private List<String> repositoryIds;

    /**
     * 群成员数（= 项目成员数）。
     */
    @Schema(description = "群成员数（= 项目成员数）")
    private Long memberCount;

    /**
     * 当前用户在该群的未读消息数（排除本人消息），≥ 0。
     */
    @Schema(description = "当前用户在该群的未读消息数（排除本人消息），≥ 0")
    private Long unreadCount;

    /**
     * 当前用户在该群被 @ 的未读消息数（排除本人消息），≥ 0；前端据此显示「有人@我」提示。
     */
    @Schema(description = "当前用户在该群被 @ 的未读消息数（排除本人消息），≥ 0")
    private Long mentionedUnread;
}
