package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群最新消息摘要（A 联调约定 §2，群列表 latestMessage）。
 * <p>
 * senderName 为用户昵称或 Agent 名称；SYSTEM 消息无发送者，senderName 为空。
 * text 仅对 TEXT/QUOTE 等含 {@code $.text} 的消息可取到，其他类型为空；
 * type 与消息类型枚举一致，客户端据此对 IMAGE/FILE 等展示 [图片]/[文件] 摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupLatestMessage {

    /**
     * 发送者昵称（用户 displayName 或 Agent name）；SYSTEM 消息为空。
     */
    @Schema(description = "发送者昵称（用户 displayName 或 Agent name）；SYSTEM 消息为空")
    private String senderName;

    /**
     * 最新消息文本摘要；非文本消息为空。
     */
    @Schema(description = "最新消息文本摘要；非文本消息为空")
    private String text;

    /**
     * 最新消息类型，取值与消息类型枚举一致：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE/DIFF/TASK_STATUS。
     */
    @Schema(description = "最新消息类型，取值与消息类型枚举一致：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE/DIFF/TASK_STATUS")
    private String type;
}
