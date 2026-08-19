package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * AGENT 交付项：团队自定义 Agent 的发布审核聚合（契约 v2.0.6 §11.1 审核化补充）。
 * <p>
 * 交付中心只展示进入共享审核流程的 Agent：PRIVATE（未提交）不进入；
 * PENDING（待审核）/TEAM（已批准共享）/ARCHIVED（已归档）进入。
 * 敏感字段（prompt）绝不进交付中心；role/description 摘要仅创建者或 Team Owner 可见。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentDeliveryItem extends DeliveryItem {

    /**
     * Agent 角色标签（DEVELOPER/TESTER/REVIEWER/GENERAL 等）。
     */
    @Schema(description = "Agent 角色标签")
    private String role;

    /**
     * Agent 用途描述摘要（创建者或 Team Owner 可见）。
     */
    @Schema(description = "Agent 用途描述摘要")
    private String descriptionExcerpt;

    /**
     * 展示用：是否系统预置 Agent（系统预置不进入发布审核，恒 false）。
     */
    @Schema(description = "是否系统预置 Agent")
    private Boolean isDefault;
}
