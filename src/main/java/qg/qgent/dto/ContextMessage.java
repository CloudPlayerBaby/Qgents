package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文中的一条消息记录（用于组装 Agent 输入）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextMessage {

    /**
     * 群内单调递增序号。
     */
    @Schema(description = "群内单调递增序号")
    private Long sequence;

    /**
     * 消息类型：TEXT/CODE/IMAGE/FILE/DIFF/TASK_STATUS/QUOTE 等。
     */
    @Schema(description = "消息类型")
    private String type;

    /**
     * 发送者类型：USER / AGENT / SYSTEM。
     */
    @Schema(description = "发送者类型：USER / AGENT / SYSTEM")
    private String senderType;

    /**
     * 发送者 ID（用户或 Agent），可为空。
     */
    @Schema(description = "发送者 ID（用户或 Agent）")
    private String senderId;

    /**
     * 消息正文（从结构化 content 提取的文本）。
     */
    @Schema(description = "消息正文")
    private String text;
}
