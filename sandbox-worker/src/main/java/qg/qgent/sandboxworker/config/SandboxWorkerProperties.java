package qg.qgent.sandboxworker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱工作节点的运行时、安全上限和资源回收配置。
 * 所有值均由部署环境控制，客户端请求不能放宽这些限制。
 */
@Data
@ConfigurationProperties(prefix = "sandbox")
public class SandboxWorkerProperties {

    /**
     * 容器运行时实现，可选值为 fake 或 docker。
     */
    private String runtime = "fake";

    /**
     * 未发生操作时的默认存活时间。
     */
    private Duration defaultIdleTtl = Duration.ofMinutes(30);

    /**
     * 控制层能够申请的最大空闲存活时间。
     */
    private Duration maxIdleTtl = Duration.ofHours(2);

    /**
     * 沙箱从创建开始计算的默认最大生命周期。
     */
    private Duration defaultMaxLifetime = Duration.ofHours(4);

    /**
     * 沙箱生命周期的 Worker 本地硬上限。
     */
    private Duration maxLifetime = Duration.ofHours(12);

    /**
     * 单次命令的默认执行超时时间。
     */
    private Duration defaultExecutionTimeout = Duration.ofMinutes(15);

    /**
     * 单次命令执行超时的 Worker 本地硬上限。
     */
    private Duration maxExecutionTimeout = Duration.ofHours(1);

    /**
     * 扫描并回收过期沙箱的间隔。
     */
    private Duration cleanupInterval = Duration.ofSeconds(30);

    /**
     * 异步命令执行线程池大小。
     */
    private int executionThreads = 4;

    /**
     * 允许控制层选择的镜像配置名称。
     */
    private Set<String> imageProfiles = Set.of("dev-tools");

    /**
     * Docker Engine API 地址。
     */
    private String dockerHost = "unix:///var/run/docker.sock";

    /**
     * 当前 Worker 的稳定编号，用于限定容器恢复和认领范围。
     */
    private String workerId = "local";

    /**
     * Worker 进程可见的 Workspace 根目录，用于路径真实性检查。
     */
    private String workspaceLocalRoot = "/var/lib/qgents/workspaces";

    /**
     * Docker daemon 所在宿主机的 Workspace 根目录，用于创建 bind mount。
     */
    private String workspaceDockerHostRoot = "/srv/qgents/workspaces";

    /**
     * Worker 保存 Workspace 清单的目录，不会挂载到 Agent 沙箱。
     */
    private String workspaceMetadataRoot = "/var/lib/qgents/workspace-metadata";

    /**
     * 后端 3 同步完成的共享裸 Git 仓库根目录。
     */
    private String gitStoreRoot = "/var/lib/qgents/git-store";

    /**
     * 镜像配置名称到实际镜像引用的映射。
     */
    private Map<String, String> images = Map.of("dev-tools", "qgents/sandbox-dev-tools:0.2.0");

    /**
     * 单个沙箱可使用的内存字节数。
     */
    private long memoryBytes = 4L * 1024 * 1024 * 1024;

    /**
     * Docker nanoCPUs 配置值，十亿表示一个 CPU 核心。
     */
    private long nanoCpus = 2_000_000_000L;

    /**
     * 单个沙箱允许创建的最大进程数量。
     */
    private long pidsLimit = 256L;

    /**
     * 网络策略：none 禁止网络，outbound 使用 Docker bridge 网络。
     */
    private String networkPolicy = "outbound";

    /**
     * stdout 和 stderr 各自在 Worker 内存中允许保留的最大字节数。
     */
    private int maxOutputBytes = 1024 * 1024;

    /**
     * 单个 Sandbox 的 Maven / Maven Wrapper 临时缓存容量上限（Docker tmpfs size，如 2g、512m 或字节数）。
     * 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
     */
    private String mavenCacheSize = "2g";

    /**
     * 单个 Sandbox 的 Gradle 临时缓存容量上限（Docker tmpfs size，如 3g、512m 或字节数）。
     * 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
     */
    private String gradleCacheSize = "3g";

    /**
     * 单个 Sandbox 的 npm 临时缓存容量上限（Docker tmpfs size，如 1g、512m 或字节数）。
     * 只作为本 Sandbox 的可写 tmpfs，Sandbox 销毁后即清理，不是宿主机持久缓存，也不与其他 Sandbox 或 Project 共享。
     */
    private String npmCacheSize = "1g";

    /** 沙箱开发用户 HOME 的临时可写容量，覆盖各构建工具的隐藏状态目录。 */
    private String developerHomeSize = "8g";

    /**
     * 主后端地址，供 Worker 访问内部接口。
     */
    private String backendUrl = "http://qgents-backend:8080";

    /**
     * Worker 与主后端通信的独立内部鉴权 Token。
     */
    private String backendServiceToken = "";
}
