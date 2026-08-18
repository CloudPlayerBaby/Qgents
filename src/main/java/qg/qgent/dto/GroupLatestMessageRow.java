package qg.qgent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 群最新消息批量查询结果行：{@code MessageMapper.selectLatestByGroupIds} 的映射结果。
 * <p>
 * 由 Service 转换为 {@link GroupLatestMessage} 供群列表展示；senderName 通过
 * LEFT JOIN users/agents 取 {@code COALESCE(display_name, name)}，SYSTEM 消息为空。
 */
@Data
public class GroupLatestMessageRow {

    /**
     * 所属需求群 ID。
     */
    private UUID requirementGroupId;

    /**
     * 最新消息发送者昵称（用户 displayName 或 Agent name）；SYSTEM 消息为空。
     */
    private String senderName;

    /**
     * 最新消息文本摘要（content 的 $.text）；非文本消息为空。
     */
    private String text;

    /**
     * 最新消息类型，供前端对非文本摘要降级展示。
     */
    private String messageType;

    /**
     * 最新消息发送时间（UTC）。
     */
    private LocalDateTime createdAt;
}
