package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 群消息视图（契约 §7）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    /**
     * 消息 ID。
     */
    @Schema(description = "消息 ID")
    private String id;

    /**
     * 所属需求群 ID。
     */
    @Schema(description = "所属需求群 ID")
    private String groupId;

    /**
     * 群内单调递增序号。
     */
    @Schema(description = "群内单调递增序号")
    private Long sequence;

    /**
     * 消息类型：TEXT/CODE/IMAGE/FILE/DIFF/TASK_STATUS/SYSTEM/QUOTE。
     */
    @Schema(description = "消息类型：TEXT/CODE/IMAGE/FILE/DIFF/TASK_STATUS/SYSTEM/QUOTE")
    private String type;

    /**
     * 结构化内容对象。
     */
    @Schema(description = "结构化内容对象")
    private Map<String, Object> content;

    /**
     * 发送者 ID；SYSTEM 消息为空。
     */
    @Schema(description = "发送者 ID；SYSTEM 消息为空")
    private String senderId;

    /**
     * 发送者类型：USER / AGENT / SYSTEM。
     */
    @Schema(description = "发送者类型：USER / AGENT / SYSTEM")
    private String senderType;

    /**
     * 发送者显示名称（用户 displayName 或 Agent name）；SYSTEM 消息为空。
     */
    @Schema(description = "发送者显示名称")
    private String senderName;

    /**
     * 回复或引用的原消息 ID；为空表示无回复。
     */
    @Schema(description = "回复或引用的原消息 ID")
    private String replyToId;

    /**
     * 提及对象列表。
     */
    @Schema(description = "提及对象列表")
    private List<Mention> mentions;

    /**
     * 发送时间（ISO8601 UTC，带时区后缀 Z）。
     */
    @Schema(description = "发送时间（ISO8601 UTC）")
    private String createdAt;
}
