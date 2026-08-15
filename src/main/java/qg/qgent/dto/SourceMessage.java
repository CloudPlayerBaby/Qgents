package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务来源消息摘要（任务详情右侧需求上下文 Tab 使用）。
 * <p>
 * 仅返回脱敏文本摘要 textExcerpt，不返回附件/原始正文；附件仍通过现有消息/附件权限接口访问。
 * sender 为发送人（用户或 Agent）的展示摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceMessage {

    /**
     * 消息 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "消息 ID")
    private String id;

    /**
     * 发送人摘要（用户或 Agent）。
     */
    @Schema(description = "发送人摘要")
    private UserSummary sender;

    /**
     * 脱敏文本摘要（截断）。
     */
    @Schema(description = "脱敏文本摘要")
    private String textExcerpt;

    /**
     * 消息发送时间（UTC）。
     */
    @Schema(description = "消息发送时间")
    private String createdAt;
}
