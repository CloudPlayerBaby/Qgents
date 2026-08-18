package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
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
    /**
     * Sandbox 使用的持久 Workspace 存储标识。
     */
    private String workspaceStorageKey;
    /**
     * Sandbox 使用的 Worker 镜像配置名称。
     */
    private String imageProfile;
    /**
     * Sandbox 绑定的项目仓库编号集合。
     */
    private List<UUID> repositoryIds;
    private String status;
    private String runtimeKind;
    private Instant createdAt;
    private Instant lastActiveAt;
    private Instant expiresAt;
    private Instant maxExpiresAt;
}
