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
 * 项目共享 Skill 实体，对应表 skills（契约 §8）。
 * <p>
 * 成员先创建 PRIVATE Skill 并装配给自己拥有的 Agent；Project Admin 可发布为 PROJECT_SHARED 供项目成员使用。
 */
@Data
@TableName(value = "skills", autoResultMap = true)
public class SkillEntity {

    /**
     * Skill ID（UUIDv7，BINARY(16)）。
     */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /**
     * 所属项目 ID（UUIDv7，BINARY(16)）。
     */
    private UUID projectId;

    /**
     * 创建用户 ID（UUIDv7，BINARY(16)）。
     */
    private UUID createdBy;

    /**
     * Skill 名称。
     */
    private String name;

    /**
     * 可复用操作规范正文（MEDIUMTEXT）。
     */
    private String content;

    /**
     * 标签 JSON 数组。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 可见性枚举：PRIVATE / PROJECT_SHARED。
     */
    private String visibility;

    /**
     * 状态枚举：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED。
     */
    private String status;

    /**
     * 最近审核用户 ID（UUIDv7，BINARY(16)）；可为空。
     */
    private UUID reviewerId;

    /**
     * 最近驳回原因；可为空。
     */
    private String rejectionReason;

    /**
     * 最近审核时间（UTC）；可为空。
     */
    private LocalDateTime reviewedAt;

    /**
     * 创建时间（UTC）。
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间（UTC）。
     */
    private LocalDateTime updatedAt;
}
