package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑自定义 Agent 请求体（契约 §11.1，接口补充 v2.0.3 §3）。
 * <p>
 * 所有字段均为可选，但请求体至少包含一个字段（服务端校验）。
 * visibility/status/createdBy/isDefault 不允许由客户端修改；role 变更只影响后续新分配的
 * TaskStep，不改变已经定型的 TaskStep.assignedAgentId。
 */
@Data
@Schema(description = "编辑自定义 Agent 请求")
public class UpdateAgentRequest {

    @Size(max = 255, message = "name 最长 255 个字符")
    @Schema(description = "Agent 昵称，去除首尾空白后非空（可选）")
    private String name;

    @Size(max = 2048, message = "avatar 最长 2048 个字符")
    @Schema(description = "合法头像 URL，传空串清空（可选）")
    private String avatar;

    @Size(max = 32, message = "role 最长 32 个字符")
    @Schema(description = "角色标签（可选），变更只影响后续新分配的 TaskStep")
    private String role;

    @Size(max = 5000, message = "description 最长 5000 个字符")
    @Schema(description = "Agent 用途描述（可选）")
    private String description;

    @Size(max = 20000, message = "prompt 最长 20000 个字符")
    @Schema(description = "Agent 系统提示词（可选）；不得包含凭据、Token、私钥或宿主机敏感信息")
    private String prompt;
}