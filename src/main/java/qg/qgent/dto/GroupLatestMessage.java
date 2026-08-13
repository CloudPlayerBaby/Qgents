package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群最新消息摘要（A 联调约定 §2，群列表 latestMessage）。
 * <p>
 * senderName 为用户昵称或 Agent 名称；SYSTEM 消息无发送者，senderName 为空。
 * text 仅对 TEXT/QUOTE 等含 {@code $.text} 的消息可取到，其他类型为空，前端按消息类型降级展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupLatestMessage {

    /** 发送者昵称（用户 displayName 或 Agent name）；SYSTEM 消息为空。 */
    @Schema(description = "发送者昵称（用户 displayName 或 Agent name）；SYSTEM 消息为空")
    private String senderName;

    /** 最新消息文本摘要；非文本消息为空。 */
    @Schema(description = "最新消息文本摘要；非文本消息为空")
    private String text;
}
