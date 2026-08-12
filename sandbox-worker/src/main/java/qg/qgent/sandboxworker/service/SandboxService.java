package qg.qgent.sandboxworker.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.ResourceLimitsRequest;
import qg.qgent.sandboxworker.api.SandboxResponse;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.ContainerRuntime;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理沙箱状态、租约和底层容器生命周期。
 * 资源请求始终受到 Worker 本地配置约束，控制层不能通过请求放宽上限。
 */
@Service
@RequiredArgsConstructor
public class SandboxService {
    private final ContainerRuntime runtime;
    private final SandboxWorkerProperties properties;
    private final Clock clock;

    /**
     * 创建沙箱并计算实际租约。
     * 相同沙箱编号和相同关键参数的重放请求由底层运行时幂等处理。
     */
    public SandboxResponse create(CreateSandboxRequest request) {
        if (!properties.getImageProfiles().contains(request.getImageProfile())) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_PROFILE_NOT_ALLOWED", "镜像配置不在 Worker 允许列表中");
        }

        Instant now = clock.instant();
        Duration idleTtl = requestedIdleTtl(request.getLimits());
        Duration maxLifetime = requestedMaxLifetime(request.getLimits());
        Duration executionTimeout = requestedExecutionTimeout(request.getLimits());
        SandboxAllocation allocation = new SandboxAllocation(
                request.getSandboxId(),
                request.getTaskRunId(),
                request.getWorkspaceStorageKey(),
                request.getImageProfile(),
                "READY",
                properties.getRuntime().toUpperCase(),
                now,
                now,
                now.plus(idleTtl),
                now.plus(maxLifetime),
                executionTimeout,
                null,
                Map.copyOf(request.getRepositories()));

        try {
            return response(runtime.create(request, allocation));
        } catch (IllegalStateException exception) {
            throw new WorkerException(HttpStatus.CONFLICT, "SANDBOX_ID_CONFLICT", exception.getMessage());
        }
    }

    /** 查询沙箱；不存在时返回空结果。 */
    public Optional<SandboxResponse> find(UUID sandboxId) {
        return runtime.find(sandboxId).map(this::response);
    }

    /** 返回 READY 状态的沙箱，否则拒绝执行兼容版命令。 */
    public SandboxAllocation requireReady(UUID sandboxId) {
        SandboxAllocation allocation = require(sandboxId);
        if (!"READY".equals(allocation.getStatus())) {
            throw new WorkerException(HttpStatus.CONFLICT, "SANDBOX_NOT_READY", "沙箱当前不能执行命令");
        }
        return allocation;
    }

    /**
     * 返回仍由 Worker 管理的沙箱。
     * 结构化工具当前允许并发执行，因此这里只校验资源存在，不检查 READY 状态。
     */
    public SandboxAllocation findAllocation(UUID sandboxId) {
        return require(sandboxId);
    }

    /** 延长空闲租约，但绝不突破最大生命周期。 */
    public SandboxResponse renew(UUID sandboxId, Long requestedSeconds) {
        SandboxAllocation allocation = require(sandboxId);
        Duration requested = requestedSeconds == null
                ? properties.getDefaultIdleTtl()
                : Duration.ofSeconds(requestedSeconds);
        Duration ttl = min(requested, properties.getMaxIdleTtl());
        Instant now = clock.instant();

        allocation.setLastActiveAt(now);
        allocation.setExpiresAt(min(now.plus(ttl), allocation.getMaxExpiresAt()));
        return response(allocation);
    }

    /** 将兼容版异步命令使用的沙箱标记为忙碌。 */
    public void markBusy(UUID sandboxId) {
        SandboxAllocation allocation = requireReady(sandboxId);
        allocation.setStatus("BUSY");
        allocation.setLastActiveAt(clock.instant());
    }

    /** 在兼容版异步命令结束后恢复沙箱状态并刷新空闲租约。 */
    public void markReady(UUID sandboxId) {
        runtime.find(sandboxId).ifPresent(allocation -> {
            Instant now = clock.instant();
            allocation.setStatus("READY");
            allocation.setLastActiveAt(now);
            allocation.setExpiresAt(min(now.plus(properties.getDefaultIdleTtl()), allocation.getMaxExpiresAt()));
        });
    }

    /** 幂等销毁沙箱容器，不删除 Workspace。 */
    public void destroy(UUID sandboxId) {
        runtime.destroy(sandboxId);
    }

    /** 返回已经超过空闲期限或最大生命周期的沙箱编号。 */
    public List<UUID> expiredSandboxIds() {
        Instant now = clock.instant();
        return runtime.findAll().stream()
                .filter(item -> !now.isBefore(item.getExpiresAt()) || !now.isBefore(item.getMaxExpiresAt()))
                .map(SandboxAllocation::getId)
                .toList();
    }

    private SandboxAllocation require(UUID sandboxId) {
        return runtime.find(sandboxId)
                .orElseThrow(() -> new WorkerException(HttpStatus.NOT_FOUND,
                        "SANDBOX_NOT_FOUND", "沙箱不存在"));
    }

    private Duration requestedIdleTtl(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getIdleTtlSeconds();
        Duration requested = seconds == null
                ? properties.getDefaultIdleTtl()
                : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxIdleTtl());
    }

    private Duration requestedMaxLifetime(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getMaxLifetimeSeconds();
        Duration requested = seconds == null
                ? properties.getDefaultMaxLifetime()
                : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxLifetime());
    }

    private Duration requestedExecutionTimeout(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getExecutionTimeoutSeconds();
        Duration requested = seconds == null
                ? properties.getDefaultExecutionTimeout()
                : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxExecutionTimeout());
    }

    private Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private SandboxResponse response(SandboxAllocation allocation) {
        return new SandboxResponse(
                allocation.getId(),
                allocation.getTaskRunId(),
                allocation.getStatus(),
                allocation.getRuntimeKind(),
                allocation.getCreatedAt(),
                allocation.getLastActiveAt(),
                allocation.getExpiresAt(),
                allocation.getMaxExpiresAt());
    }
}
