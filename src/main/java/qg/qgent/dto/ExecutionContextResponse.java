package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 与 Sandbox 的只读状态摘要。
 * 不返回宿主机路径、容器控制入口或任何凭据；未由执行服务填充的字段为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContextResponse {
    private String startedAt;
    private String expiresAt;
}
