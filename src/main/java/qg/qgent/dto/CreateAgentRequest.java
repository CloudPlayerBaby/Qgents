package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建自定义 Agent 请求体（契约 §11.1，接口补充 v2.0.3 §2）。
 * <p>
 * 创建成功后固定为 {@code visibility=PRIVATE}、{@code status=ACTIVE}、{@code isDefault=false}。
 * role 只是调度与工具权限的角色标签，不是客户端可自行扩大的权限；prompt 不得包含凭据、
 * Token、私钥或宿主机敏感信息（服务端校验）。
 */
@Data
@Schema(description = "创建自定义 Agent 请求")
public class CreateAgentRequest {

    @NotBlank(message = "name 不能为空白")
    @Size(max = 255, message = "name 最长 255 个字符")
    @Schema(description = "Agent 昵称，去除首尾空白后非空", example = "Java 后端 Agent")
    private String name;

    @Size(max = 2048, message = "avatar 最长 2048 个字符")
    @Schema(description = "合法头像 URL，可为 null", example = "https://cdn.example.com/avatars/java.png")
    private String avatar;

    @NotBlank(message = "role 不能为空白")
    @Size(max = 32, message = "role 最长 32 个字符")
    @Schema(description = "角色标签：ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER/GENERAL",
            example = "DEVELOPER")
    private String role;

    @Size(max = 5000, message = "description 最长 5000 个字符")
    @Schema(description = "Agent 用途描述（展示与选用决策依据），可为 null")
    private String description;

    @NotBlank(message = "prompt 不能为空白")
    @Size(max = 20000, message = "prompt 最长 20000 个字符")
    @Schema(description = "Agent 系统提示词；不得包含凭据、Token、私钥或宿主机敏感信息")
    private String prompt;
}