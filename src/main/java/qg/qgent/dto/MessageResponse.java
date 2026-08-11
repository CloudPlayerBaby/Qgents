package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 群消息视图（契约 §7）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    /** 消息 ID。 */
    private String id;

    /** 所属需求群 ID。 */
    private String groupId;

    /** 群内单调递增序号。 */
    private Long sequence;

    /** 消息类型：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE。 */
    private String type;

    /** 结构化内容对象。 */
    private Map<String, Object> content;

    /** 发送者 ID；SYSTEM 消息为空。 */
    private String senderId;

    /** 发送者类型：USER / SYSTEM（Agent 后续扩展）。 */
    private String senderType;

    /** 回复或引用的原消息 ID；为空表示无回复。 */
    private String replyToId;

    /** 提及对象列表。 */
    private List<Mention> mentions;

    /** 发送时间（UTC）。 */
    private LocalDateTime createdAt;
}
