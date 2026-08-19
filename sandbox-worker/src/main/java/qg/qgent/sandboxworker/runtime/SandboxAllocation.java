package qg.qgent.sandboxworker.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 运行时维护的沙箱分配状态。
 * runtimeHandle 和实际路径属于 Worker 内部信息，不得出现在外部响应中。
 */
@Data
@AllArgsConstructor
public class SandboxAllocation {
    /**
     * 沙箱唯一编号。
     */
    private UUID id;
    /**
     * 兼容既有 Worker 协议的会话关联编号；Task 编排路径中该值可能是 Task ID。
     */
    private UUID taskRunId;
    /**
     * Sandbox 所属 Task 编号；旧容器和独立测试执行可以为空。
     */
    private UUID taskId;
    /**
     * 控制层提供的不透明 Workspace 存储键。
     */
    private String workspaceStorageKey;
    /**
     * 创建容器时选择的镜像配置名称。
     */
    private String imageProfile;
    /**
     * Worker 内部状态，例如 READY 或 BUSY。
     */
    private String status;
    /**
     * 实际运行时种类，例如 FAKE 或 DOCKER。
     */
    private String runtimeKind;
    /**
     * 沙箱创建时间，使用 UTC。
     */
    private Instant createdAt;
    /**
     * 最近一次活动时间，使用 UTC。
     */
    private Instant lastActiveAt;
    /**
     * 当前空闲租约到期时间，使用 UTC。
     */
    private Instant expiresAt;
    /**
     * 不可延长的最大生命周期终点，使用 UTC。
     */
    private Instant maxExpiresAt;
    /**
     * 单次命令允许使用的最大执行时间。
     */
    private Duration executionTimeout;
    /**
     * 底层运行时资源句柄；Docker 实现中为容器编号。
     */
    private String runtimeHandle;

    /**
     * 项目仓库编号到 Workspace 内相对仓库目录的不可变映射。
     */
    private Map<UUID, String> repositoryPaths;
}
