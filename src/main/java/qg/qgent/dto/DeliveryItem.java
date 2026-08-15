package qg.qgent.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交付中心聚合项基类（契约 v1.8.0 §20，成员 B B01）。
 * <p>
 * 以 {@code resourceType}（CODE/MEMORY/SKILL）为 discriminator 的 union：
 * 序列化时自动携带 resourceType 字段，子类各自附加专属字段，
 * 不把三类资源的专属字段堆叠为公共可选字段。
 * 所有展示字段均为脱敏摘要，不包含完整 Memory/Skill 内容、Prompt、Token、凭据或代码 Patch。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "resourceType", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CodeDeliveryItem.class, name = "CODE"),
        @JsonSubTypes.Type(value = MemoryDeliveryItem.class, name = "MEMORY"),
        @JsonSubTypes.Type(value = SkillDeliveryItem.class, name = "SKILL")
})
public abstract class DeliveryItem {

    /**
     * 交付项 ID（资源 ID 的字符串形式，与 resourceId 一致）。
     */
    @Schema(description = "交付项 ID")
    private String id;

    /**
     * 所属项目 ID。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * 资源类型：CODE / MEMORY / SKILL（union discriminator）。
     */
    @Schema(description = "资源类型：CODE / MEMORY / SKILL")
    private String resourceType;

    /**
     * 真实资源 ID：CODE 为 DiffReviewBatch ID，MEMORY 为 memoryId，SKILL 为 skillId。
     */
    @Schema(description = "真实资源 ID（CODE=批次 ID，MEMORY=memoryId，SKILL=skillId）")
    private String resourceId;

    /**
     * 展示标题。
     */
    @Schema(description = "展示标题")
    private String title;

    /**
     * 脱敏摘要（≤200 字符）；无则 null。
     */
    @Schema(description = "脱敏摘要")
    private String summary;

    /**
     * 资源版本；当前无版本数据源，恒为 null。
     */
    @Schema(description = "资源版本，当前恒为 null")
    private String version;

    /**
     * 后端统一派生的展示状态（前端不维护映射）。
     */
    @Schema(description = "后端派生的展示状态")
    private String displayStatus;

    /**
     * 真实资源状态（如 reviewStatus / Memory.status / Skill.status）。
     */
    @Schema(description = "真实资源状态")
    private String resourceStatus;

    /**
     * 来源需求群摘要；无需求群来源时 null。
     */
    @Schema(description = "来源需求群摘要")
    private RequirementGroupRef requirementGroup;

    /**
     * 来源关联（任务/运行/步骤/消息/产物）；无对应关联时字段为 null。
     */
    @Schema(description = "来源关联")
    private DeliverySource source;

    /**
     * 创建者摘要。
     */
    @Schema(description = "创建者摘要")
    private UserSummary creator;

    /**
     * 提交审核者摘要；当前无独立提交者数据源，恒为 null。
     */
    @Schema(description = "提交审核者摘要，当前恒为 null")
    private UserSummary submitter;

    /**
     * 最近审核人摘要；未审核时 null。
     */
    @Schema(description = "最近审核人摘要")
    private UserSummary reviewer;

    /**
     * 驳回/拒绝原因；未拒绝时 null。
     */
    @Schema(description = "驳回/拒绝原因")
    private String reviewReason;

    /**
     * 创建时间（ISO8601 UTC）。
     */
    @Schema(description = "创建时间")
    private String createdAt;

    /**
     * 提交审核时间；当前无独立提交时间数据源，恒为 null。
     */
    @Schema(description = "提交审核时间，当前恒为 null")
    private String submittedAt;

    /**
     * 审核时间；未审核时 null。
     */
    @Schema(description = "审核时间")
    private String reviewedAt;

    /**
     * 更新时间（ISO8601 UTC）。
     */
    @Schema(description = "更新时间")
    private String updatedAt;

    /**
     * 服务端按正式资源接口规则 + 当前用户角色派生的操作能力。
     */
    @Schema(description = "操作能力（后端派生）")
    private DeliveryCapabilities capabilities;

    /**
     * 显式打开目标（四态），resourceId 不再被多义解释。
     */
    @Schema(description = "显式打开目标")
    private DeliveryOpenTarget openTarget;

    /**
     * 需求群摘要引用。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequirementGroupRef {
        @Schema(description = "需求群 ID")
        private String id;
        @Schema(description = "需求群名称")
        private String name;
    }

    /**
     * 交付项来源关联摘要。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliverySource {
        @Schema(description = "来源任务 ID")
        private String taskId;
        @Schema(description = "来源任务展示编号（仅展示用）")
        private String taskDisplayCode;
        @Schema(description = "来源任务标题")
        private String taskTitle;
        @Schema(description = "来源任务运行 ID")
        private String taskRunId;
        @Schema(description = "来源任务步骤 ID")
        private String taskStepId;
        @Schema(description = "来源消息 ID")
        private String messageId;
        @Schema(description = "来源执行产物 ID")
        private String artifactId;
    }
}
