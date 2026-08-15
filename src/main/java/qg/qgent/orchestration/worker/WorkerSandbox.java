package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Worker 返回的 Sandbox 状态（镜像 Worker 的 Sandbox）。不包含宿主机路径或凭证。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerSandbox {

    /**
     * Sandbox 编号。
     */
    private UUID id;

    /**
     * 关联的任务运行编号。
     */
    private UUID taskRunId;

    /**
     * 生命周期状态。
     */
    private String status;

    /**
     * 运行时实现类型（fake / docker）。
     */
    private String runtimeKind;

    /**
     * 创建时间（ISO-8601 字符串）。
     */
    private String createdAt;

    /**
     * 最近活跃时间（ISO-8601 字符串）。
     */
    private String lastActiveAt;

    /**
     * 空闲租约到期时间（ISO-8601 字符串）。
     */
    private String expiresAt;

    /**
     * 生命周期硬上限时间（ISO-8601 字符串）。
     */
    private String maxExpiresAt;
}
