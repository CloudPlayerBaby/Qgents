package qg.qgent.sandboxworker.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 通过 Docker Engine API 管理真实沙箱容器。
 * 容器元数据同时写入 Docker 标签，使 Worker 重启后可以重新认领仍在运行的容器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "docker")
public class DockerContainerRuntime implements ContainerRuntime {
    private static final String LABEL_PREFIX = "qgents.";
    private static final String MANAGED_LABEL = LABEL_PREFIX + "managed";
    private static final String WORKER_LABEL = LABEL_PREFIX + "worker-id";
    private static final String SANDBOX_LABEL = LABEL_PREFIX + "sandbox-id";
    private static final String TASK_RUN_LABEL = LABEL_PREFIX + "task-run-id";
    private static final String WORKSPACE_LABEL = LABEL_PREFIX + "workspace-storage-key";
    private static final String IMAGE_PROFILE_LABEL = LABEL_PREFIX + "image-profile";
    private static final String CREATED_AT_LABEL = LABEL_PREFIX + "created-at";
    private static final String LAST_ACTIVE_AT_LABEL = LABEL_PREFIX + "last-active-at";
    private static final String EXPIRES_AT_LABEL = LABEL_PREFIX + "expires-at";
    private static final String MAX_EXPIRES_AT_LABEL = LABEL_PREFIX + "max-expires-at";
    private static final String EXECUTION_TIMEOUT_LABEL = LABEL_PREFIX + "execution-timeout-seconds";
    private static final String REPOSITORY_LABEL_PREFIX = LABEL_PREFIX + "repository.";
    /**
     * 沙箱容器运行用户（与 sandbox-images/dev-tools Dockerfile 的 USER_ID 一致），
     * workspace 仓库目录属主需与之一致，非 root 沙箱才能读写 git 检出的文件。
     */
    private static final int SANDBOX_UID = 10001;
    private static final int SANDBOX_GID = 10001;

    private final DockerClient docker;
    private final SandboxWorkerProperties properties;
    private final WorkspacePathResolver paths;
    private final SandboxBindFactory bindFactory;
    private final ConcurrentMap<UUID, SandboxAllocation> allocations = new ConcurrentHashMap<>();

    /**
     * 创建一个非特权沙箱容器，并把唯一允许持久写入的 Workspace 挂载到容器内。
     * 相同 Sandbox 编号的重复请求会返回冲突。
     */
    @Override
    public synchronized SandboxAllocation create(CreateSandboxRequest request, SandboxAllocation allocation) {
        SandboxAllocation existing = allocations.get(request.getSandboxId());
        if (existing != null) {
            throw new WorkerException(CONFLICT, "SANDBOX_ID_CONFLICT", "沙箱编号已经存在");
        }

        paths.resolveLocal(request.getWorkspaceStorageKey());
        for (UUID repositoryId : allocation.getRepositoryPaths().keySet()) {
            grantSandboxOwnership(repositoryId, paths.resolveRepositoryLocal(allocation, repositoryId));
        }
        String image = Optional.ofNullable(properties.getImages().get(request.getImageProfile()))
                .orElseThrow(() -> new WorkerException(CONFLICT, "IMAGE_PROFILE_NOT_CONFIGURED", "镜像配置尚未映射到镜像"));
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(bindFactory.create(allocation))
                .withReadonlyRootfs(true)
                .withNetworkMode(networkMode())
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withMemory(properties.getMemoryBytes())
                .withNanoCPUs(properties.getNanoCpus())
                .withPidsLimit(properties.getPidsLimit())
                .withTmpFs(tmpfsMounts());

        String containerId = null;
        try {
            containerId = docker.createContainerCmd(image)
                    .withName("qgents-sandbox-" + request.getSandboxId())
                    .withUser("10001:10001")
                    .withWorkingDir("/workspace")
                    .withLabels(labels(allocation))
                    .withHostConfig(hostConfig)
                    .withCmd("sleep", "infinity")
                    .exec().getId();
            docker.startContainerCmd(containerId).exec();
            allocation.setRuntimeHandle(containerId);
            allocations.put(allocation.getId(), allocation);
            log.info("sandbox container started sandboxId={} taskRunId={} workspace={} containerId={}",
                    allocation.getId(), allocation.getTaskRunId(), allocation.getWorkspaceStorageKey(), containerId);
            return allocation;
        } catch (RuntimeException exception) {
            log.warn("DOCKER_CREATE_FAILED sandboxId={} workspace={} category={}",
                    allocation.getId(), allocation.getWorkspaceStorageKey(), exception.getClass().getSimpleName());
            removeContainerQuietly(containerId);
            throw new WorkerException(BAD_GATEWAY, "DOCKER_CREATE_FAILED", "Docker Engine 创建沙箱失败");
        }
    }

    /**
     * 将仓库目录及其文件属主递归改为沙箱运行用户（uid 10001），保证非 root 沙箱可读写。
     * worker 以 root 运行 git worktree add 时落盘文件属主为 root，若沙箱保持 10001 运行则无法修改这些文件；
     * 在宿主机原生文件系统（Linux 服务器）上 chown 真实生效；Docker Desktop 的 Windows 挂载不支持 chown，静默跳过。
     */
    private void grantSandboxOwnership(UUID repositoryId, Path repository) {
        try (var stream = Files.walk(repository)) {
            int failed = 0;
            for (Path path : stream.filter(p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try {
                    Files.setAttribute(path, "unix:uid", SANDBOX_UID, LinkOption.NOFOLLOW_LINKS);
                    Files.setAttribute(path, "unix:gid", SANDBOX_GID, LinkOption.NOFOLLOW_LINKS);
                } catch (Exception e) {
                    failed++;
                }
            }
            if (failed > 0) {
                log.warn("sandbox ownership partially granted repositoryId={}, {} paths skipped", repositoryId, failed);
            }
        } catch (Exception e) {
            log.warn("grant sandbox ownership failed repositoryId={} category={}",
                    repositoryId, e.getClass().getSimpleName());
        }
    }

    /**
     * 构建容器可写 tmpfs 挂载：/tmp、/run、开发用户 HOME 以及构建缓存目录。
     * 各构建缓存容量由 Worker 配置控制，仅作为本 Sandbox 的临时缓存，随容器销毁清理；
     * 不挂载宿主机路径，不放开 rootfs 其他位置的只读隔离。
     */
    Map<String, String> tmpfsMounts() {
        String developerCacheOptions = "rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=";
        return Map.of(
                "/tmp", "rw,noexec,nosuid,size=512m",
                "/var/tmp", "rw,noexec,nosuid,size=512m",
                "/run", "rw,noexec,nosuid,size=64m",
                "/home/developer", developerCacheOptions + properties.getDeveloperHomeSize(),
                "/home/developer/.m2", developerCacheOptions + properties.getMavenCacheSize(),
                "/home/developer/.gradle", developerCacheOptions + properties.getGradleCacheSize(),
                "/home/developer/.npm", developerCacheOptions + properties.getNpmCacheSize(),
                "/home/developer/.cache", developerCacheOptions + "512m",
                "/opt/pnpm", developerCacheOptions + "1g");
    }

    @Override
    public Optional<SandboxAllocation> find(UUID sandboxId) {
        return Optional.ofNullable(allocations.get(sandboxId));
    }

    @Override
    public List<SandboxAllocation> findAll() {
        return List.copyOf(allocations.values());
    }

    /**
     * 从 Docker daemon 全局查询所有 Worker 创建的运行中容器，避免只检查当前 JVM 内存。
     */
    @Override
    public boolean isWorkspaceInUse(String workspaceStorageKey) {
        return docker.listContainersCmd()
                .withShowAll(false)
                .withLabelFilter(Map.of(MANAGED_LABEL, "true", WORKSPACE_LABEL, workspaceStorageKey))
                .exec().stream().findAny().isPresent();
    }

    /**
     * 销毁 Sandbox 容器。Workspace 是独立宿主机挂载目录，不会随容器删除。
     */
    @Override
    public synchronized void destroy(UUID sandboxId) {
        SandboxAllocation allocation = allocations.remove(sandboxId);
        if (allocation == null || allocation.getRuntimeHandle() == null) {
            return;
        }
        try {
            docker.removeContainerCmd(allocation.getRuntimeHandle()).withForce(true).withRemoveVolumes(true).exec();
            log.info("sandbox container removed sandboxId={} containerId={}",
                    sandboxId, allocation.getRuntimeHandle());
        } catch (RuntimeException exception) {
            allocations.putIfAbsent(sandboxId, allocation);
            throw new WorkerException(BAD_GATEWAY, "DOCKER_DESTROY_FAILED", "Docker Engine 销毁沙箱失败");
        }
    }

    /**
     * Worker 启动时重新认领带有受管标签且仍在运行的容器。
     * 标签不完整、已停止或已经过期的容器会被清理，避免形成无法管理的孤儿资源。
     */
    @PostConstruct
    void recoverManagedContainers() {
        List<Container> containers;
        try {
            containers = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(MANAGED_LABEL, "true", WORKER_LABEL, properties.getWorkerId()))
                    .exec();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("无法扫描 Docker 受管容器", exception);
        }

        Instant now = Instant.now();
        for (Container container : containers) {
            try {
                SandboxAllocation allocation = allocationFrom(container);
                Boolean running = docker.inspectContainerCmd(container.getId()).exec().getState().getRunning();
                if (!Boolean.TRUE.equals(running) || !now.isBefore(allocation.getMaxExpiresAt())) {
                    removeContainerQuietly(container.getId());
                    continue;
                }
                allocations.put(allocation.getId(), allocation);
            } catch (RuntimeException exception) {
                log.warn("清理无法恢复的受管容器，containerId={}", container.getId());
                removeContainerQuietly(container.getId());
            }
        }
    }

    private SandboxAllocation allocationFrom(Container container) {
        Map<String, String> labels = container.getLabels();
        UUID sandboxId = UUID.fromString(requiredLabel(labels, SANDBOX_LABEL));
        UUID taskRunId = UUID.fromString(requiredLabel(labels, TASK_RUN_LABEL));
        Instant createdAt = Instant.parse(requiredLabel(labels, CREATED_AT_LABEL));
        Instant maxExpiresAt = Instant.parse(requiredLabel(labels, MAX_EXPIRES_AT_LABEL));
        Instant recoveredAt = Instant.now();
        Instant recoveredExpiresAt = recoveredAt.plus(properties.getDefaultIdleTtl());
        if (recoveredExpiresAt.isAfter(maxExpiresAt)) {
            recoveredExpiresAt = maxExpiresAt;
        }
        return new SandboxAllocation(sandboxId, taskRunId, requiredLabel(labels, WORKSPACE_LABEL),
                requiredLabel(labels, IMAGE_PROFILE_LABEL), "READY", "DOCKER", createdAt,
                recoveredAt, recoveredExpiresAt, maxExpiresAt,
                Duration.ofSeconds(Long.parseLong(requiredLabel(labels, EXECUTION_TIMEOUT_LABEL))), container.getId(),
                repositoryPaths(labels));
    }

    private Map<String, String> labels(SandboxAllocation allocation) {
        Map<String, String> labels = new HashMap<>();
        labels.put(MANAGED_LABEL, "true");
        labels.put(WORKER_LABEL, properties.getWorkerId());
        labels.put(SANDBOX_LABEL, allocation.getId().toString());
        labels.put(TASK_RUN_LABEL, allocation.getTaskRunId().toString());
        labels.put(WORKSPACE_LABEL, allocation.getWorkspaceStorageKey());
        labels.put(IMAGE_PROFILE_LABEL, allocation.getImageProfile());
        labels.put(CREATED_AT_LABEL, allocation.getCreatedAt().toString());
        labels.put(LAST_ACTIVE_AT_LABEL, allocation.getLastActiveAt().toString());
        labels.put(EXPIRES_AT_LABEL, allocation.getExpiresAt().toString());
        labels.put(MAX_EXPIRES_AT_LABEL, allocation.getMaxExpiresAt().toString());
        labels.put(EXECUTION_TIMEOUT_LABEL, Long.toString(allocation.getExecutionTimeout().toSeconds()));
        allocation.getRepositoryPaths().forEach((id, path) -> labels.put(REPOSITORY_LABEL_PREFIX + id, path));
        return labels;
    }

    private Map<UUID, String> repositoryPaths(Map<String, String> labels) {
        Map<UUID, String> repositories = new HashMap<>();
        labels.forEach((name, value) -> {
            if (name.startsWith(REPOSITORY_LABEL_PREFIX)) {
                repositories.put(UUID.fromString(name.substring(REPOSITORY_LABEL_PREFIX.length())), value);
            }
        });
        return Map.copyOf(repositories);
    }

    private String requiredLabel(Map<String, String> labels, String name) {
        String value = labels == null ? null : labels.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("容器缺少恢复标签：" + name);
        }
        return value;
    }

    private void removeContainerQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException exception) {
            log.warn("清理 Docker 容器失败，containerId={}", containerId);
        }
    }

    private String networkMode() {
        return switch (properties.getNetworkPolicy()) {
            case "none" -> "none";
            case "outbound" -> "bridge";
            default -> throw new WorkerException(CONFLICT, "NETWORK_POLICY_INVALID", "Worker 网络策略配置不合法");
        };
    }
}
