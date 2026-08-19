package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public Agent card returned by team Agent endpoints.
 * 构造器为显式 10 参（不含审核字段），审核字段通过 setter 填充，避免历史调用因字段追加而编译失败。
 */
@Data
@NoArgsConstructor
public class AgentResponse {
    @Schema(description = "Agent ID")
    private String id;
    private String name;
    private String avatar;
    private String role;
    private String description;
    private String prompt;
    private String visibility;
    private String status;
    private String createdBy;
    /**
     * 是否为团队默认（系统预置）Agent；自定义 Agent 恒为 false，
     * 系统预置 Agent 不可通过管理接口编辑/发布/收回/归档。
     */
    @Schema(description = "是否为团队默认（系统预置）Agent")
    private Boolean isDefault;
    /**
     * 发布审核拒绝原因（仅创建者可见；批准/未审核为空）。
     */
    @Schema(description = "发布审核拒绝原因（仅创建者可见）")
    private String reviewReason;
    /**
     * 发布审核人（Team Owner）ID；未审核为空。
     */
    @Schema(description = "发布审核人 ID")
    private String reviewedBy;
    /**
     * 发布审核时间（ISO8601 UTC）；未审核为空。
     */
    @Schema(description = "发布审核时间（ISO8601 UTC）")
    private String reviewedAt;

    public AgentResponse(String id, String name, String avatar, String role, String description, String prompt,
                         String visibility, String status, String createdBy, Boolean isDefault) {
        this.id = id;
        this.name = name;
        this.avatar = avatar;
        this.role = role;
        this.description = description;
        this.prompt = prompt;
        this.visibility = visibility;
        this.status = status;
        this.createdBy = createdBy;
        this.isDefault = isDefault;
    }
}
