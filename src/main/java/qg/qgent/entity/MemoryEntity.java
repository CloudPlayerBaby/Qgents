package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 项目确认知识实体，对应表 memories（契约 §9）。
 * <p>
 * Memory 是经人工确认后供项目复用的知识，不是原始聊天记录；AI 可生成草稿但不得直接批准。
 */
@Data
@TableName(value = "memories", autoResultMap = true)
public class MemoryEntity {

    /** Memory ID（UUIDv7，BINARY(16)）。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属项目 ID（UUIDv7，BINARY(16)）。 */
    private UUID projectId;

    /** 创建用户 ID（UUIDv7，BINARY(16)）。 */
    private UUID createdBy;

    /** 知识标题。 */
    private String title;

    /** 经确认的项目事实正文（MEDIUMTEXT）。 */
    private String content;

    /** 知识分类标识，如 ENGINEERING_DECISION。 */
    private String category;

    /** 标签 JSON 数组。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /** 状态枚举：DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED。 */
    private String status;

    /** 最近审核用户 ID（UUIDv7，BINARY(16)）；可为空。 */
    private UUID reviewerId;

    /** 最近驳回原因；可为空。 */
    private String rejectionReason;

    /** 最近审核时间（UTC）；可为空。 */
    private LocalDateTime reviewedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
