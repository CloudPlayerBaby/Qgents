package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 需求群有序消息实体，对应表 messages（契约 §7 消息收发）。
 * <p>
 * sequence_no 在群内单调递增（服务端在持有群行锁时计算），客户端可通过 client_message_id 幂等重试。
 */
@Data
@TableName("messages")
public class MessageEntity {

    /** 消息 ID（UUIDv7，BINARY(16)）。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属需求群 ID（UUIDv7，BINARY(16)）。 */
    private UUID requirementGroupId;

    /** 群内单调递增消息序号（BIGINT），(requirement_group_id, sequence_no) 唯一。 */
    private Long sequenceNo;

    /** 发送用户 ID（UUIDv7，BINARY(16)）；SYSTEM 消息为空，且仅 SYSTEM 允许为空（表 CHECK）。 */
    private UUID authorUserId;

    /** 客户端幂等 ID（群内唯一），断线重试命中时返回原消息；可为空表示不启用客户端幂等。 */
    private String clientMessageId;

    /** 消息类型枚举：TEXT/CODE/IMAGE/FILE/SYSTEM/QUOTE。 */
    private String messageType;

    /** 结构化内容 JSON 文本（JSON 列），如 {"text":"..."}，按类型校验。 */
    private String content;

    /** 提及对象 JSON 文本（JSON 列），元素含 type(USER/AGENT) 与 id；可为空。 */
    private String mentions;

    /** 回复或引用的原消息 ID（UUIDv7，BINARY(16)）；可为空。 */
    private UUID replyToMessageId;

    /** 发送时间（UTC，数据库默认值）。 */
    private LocalDateTime createdAt;
}
