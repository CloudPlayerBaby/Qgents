package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发送消息请求（契约 §7）。
 * <p>
 * content 为按类型校验的结构化对象，如 {@code {"text":"..."}}；client_message_id 在同一群内唯一，
 * 断线重试命中时返回原消息。
 */
@Data
public class MessageSendRequest {

    /** 消息类型：TEXT/CODE/IMAGE/FILE/QUOTE（SYSTEM 由服务端维护）。 */
    @NotBlank
    @Size(max = 32)
    private String type;

    /** 按类型校验的结构化内容对象，不能为空。 */
    @NotNull
    private Map<String, Object> content;

    /** 提及对象列表（可空）。 */
    private List<Mention> mentions;

    /** 回复或引用的原消息 ID（可空），必须属于同一群。 */
    private UUID replyToId;

    /** 客户端幂等 ID（≤128，可空），同一群内唯一。 */
    @Size(max = 128)
    private String clientMessageId;
}
