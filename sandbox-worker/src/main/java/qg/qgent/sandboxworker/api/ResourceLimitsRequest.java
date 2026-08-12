package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 控制面请求的资源限制，工作节点会再次按本地上限收紧。 */
@Data
public class ResourceLimitsRequest {
    @Min(1)
    @Max(86400)
    private Long idleTtlSeconds;
    @Min(1)
    @Max(86400)
    private Long maxLifetimeSeconds;
    @Min(1)
    @Max(86400)
    private Long executionTimeoutSeconds;
}
