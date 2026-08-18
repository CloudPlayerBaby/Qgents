package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public Agent card returned by team Agent endpoints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}
