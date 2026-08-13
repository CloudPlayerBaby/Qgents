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
import qg.qgent.sandboxworker.workspace.WorkspaceOperationLock;
import qg.qgent.sandboxworker.workspace.WorkspaceMetadataStore;

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
    private final WorkspaceOperationLock workspaceLock;
    private final WorkspaceMetadataStore workspaceMetadataStore;

    /**
     * 创建沙箱并计算实际租约。
     * 相同 Sandbox 编号的重复创建请求会返回冲突。
     */
    public SandboxResponse create(CreateSandboxRequest request) {
        if (request.getRepositoryIds().stream().distinct().count() != request.getRepositoryIds().size()) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "SANDBOX_REPOSITORY_DUPLICATE", "Sandbox 不能重复声明同一仓库");
        }
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
                workspaceMetadataStore.resolveRepositories(request.getWorkspaceStorageKey(), request.getRepositoryIds()));

        try {
            return workspaceLock.execute(request.getWorkspaceStorageKey(),
                    () -> response(runtime.create(request, allocation)));
        } catch (IllegalStateException exception) {
            throw new WorkerException(HttpStatus.CONFLICT, "SANDBOX_ID_CONFLICT", exception.getMessage());
        }
    }

    /** 查询沙箱；不存在时返回空结果。 */
    public Optional<SandboxResponse> find(UUID sandboxId) {
        return runtime.find(sandboxId).map(this::response);
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

    /** 销毁 Sandbox 容器，不删除 Workspace。 */
    public void destroy(UUID sandboxId) {
        runtime.destroy(sandboxId);
    }

    /** 判断指定持久 Workspace 是否仍被任一受管 Sandbox 引用。 */
    public boolean isWorkspaceInUse(String workspaceStorageKey) {
        return runtime.isWorkspaceInUse(workspaceStorageKey);
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
