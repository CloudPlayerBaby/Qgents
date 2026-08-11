package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 附件元数据实体，对应表 attachments（契约 §7 附件直传凭证）。
 * <p>
 * 上传前先落 PENDING 记录并签发对象存储直传凭证，上传完成前不绑定消息。
 */
@Data
@TableName("attachments")
public class AttachmentEntity {

    /** 附件 ID（UUIDv7，BINARY(16)）。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 附件所属项目 ID（UUIDv7，BINARY(16)），用于上传前权限隔离。 */
    private UUID projectId;

    /** 发送消息后绑定的消息 ID（UUIDv7，BINARY(16)）；上传阶段为空。 */
    private UUID messageId;

    /** 上传用户 ID（UUIDv7，BINARY(16)）。 */
    private UUID uploadedBy;

    /** 对象存储内部键，不含临时访问凭证，全局唯一。 */
    private String objectKey;

    /** 原始文件名。 */
    private String fileName;

    /** MIME 媒体类型，可为空。 */
    private String mediaType;

    /** 文件大小（字节），可为空。 */
    private Long sizeBytes;

    /** 附件状态枚举：PENDING/READY/FAILED/DELETED；创建凭证时为 PENDING。 */
    private String status;

    /** 附件扩展 JSON 文本（JSON 列），如图片宽高、内容哈希；可为空。 */
    private String metadata;

    /** 上传记录创建时间（UTC，数据库默认值）。 */
    private LocalDateTime createdAt;
}
