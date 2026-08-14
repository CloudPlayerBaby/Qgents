package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 展示摘要（任务步骤/任务运行通用）。
 * <p>
 * role 为 Agent 声明的执行角色（如 DEVELOPER/TESTER/REVIEWER），与 TaskStep.role 匹配；
 * status 为 Agent 生命周期状态（ACTIVE 等）。avatarUrl 由 Agent 头像字段映射，可为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSummary {

    /** Agent ID（UUIDv7，字符串形式）。 */
    @Schema(description = "Agent ID")
    private String id;

    /** Agent 展示名称。 */
    @Schema(description = "Agent 展示名称")
    private String name;

    /** Agent 声明的角色。 */
    @Schema(description = "Agent 角色")
    private String role;

    /** Agent 头像 URL，可为 null。 */
    @Schema(description = "Agent 头像 URL")
    private String avatarUrl;

    /** Agent 生命周期状态，如 ACTIVE。 */
    @Schema(description = "Agent 状态")
    private String status;
}
