package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文中的一条消息记录（用于组装 Agent 输入）。
 * <p>
 * IMAGE/FILE 消息除 {@code text}（无正文时为空串）外，还携带从结构化 content 提取的
 * {@code attachmentId} / {@code fileName} / {@code mediaType}，供多模态输入链路按附件 ID 读取
 * 图片/文件字节喂给多模态模型；无 attachmentId 的存量消息这三项为空。
 */
@Data
@NoArgsConstructor
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
     * 消息正文（从结构化 content 提取的文本；IMAGE/FILE 无正文时为为空串，由渲染层生成附件引用）。
     */
    @Schema(description = "消息正文")
    private String text;

    /**
     * 附件 ID（IMAGE/FILE 消息，UUID 字符串）；其他类型为空。供多模态链路按此读取附件字节。
     */
    @Schema(description = "附件 ID（IMAGE/FILE 消息）")
    private String attachmentId;

    /**
     * 附件原始文件名（IMAGE/FILE 消息），可为空。
     */
    @Schema(description = "附件原始文件名（IMAGE/FILE 消息）")
    private String fileName;

    /**
     * 附件 MIME 类型（IMAGE/FILE 消息），可为空。
     */
    @Schema(description = "附件 MIME 类型（IMAGE/FILE 消息）")
    private String mediaType;

    public ContextMessage(Long sequence, String type, String senderType, String senderId, String text) {
        this(sequence, type, senderType, senderId, text, null, null, null);
    }

    public ContextMessage(Long sequence, String type, String senderType, String senderId, String text,
                          String attachmentId, String fileName, String mediaType) {
        this.sequence = sequence;
        this.type = type;
        this.senderType = senderType;
        this.senderId = senderId;
        this.text = text;
        this.attachmentId = attachmentId;
        this.fileName = fileName;
        this.mediaType = mediaType;
    }
}
