package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 沙箱的内部服务响应，不包含宿主机路径或凭证。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SandboxResponse {
    private UUID id;
    private UUID taskRunId;
    private String status;
    private String runtimeKind;
    private Instant createdAt;
    private Instant lastActiveAt;
    private Instant expiresAt;
    private Instant maxExpiresAt;
}
