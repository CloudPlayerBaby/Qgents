package qg.qgent.orchestration.worker;

import lombok.Data;

/**
 * 创建 Sandbox 时可选申请的资源上限（镜像 Worker 的 ResourceLimits）。
 * Worker 会再次按本地上限收紧，客户端请求不能放宽限制。
 */
@Data
public class WorkerResourceLimits {

    /** 空闲存活秒数。 */
    private Long idleTtlSeconds;

    /** 最大生命周期秒数。 */
    private Long maxLifetimeSeconds;

    /** 单次执行超时秒数。 */
    private Long executionTimeoutSeconds;
}
