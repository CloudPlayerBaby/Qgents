package qg.qgent.orchestration.worker;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 主后端调用后端2 Sandbox Worker 的客户端配置。
 * <p>
 * 本配置只描述接入方式与开关，不包含任何凭证：Worker 内部接口是内网受控面，
 * 主后端只通过 base-url 访问，调用方不提交宿主机路径、Git 远端或凭证。
 * {@code enabled} 用于部署配置的显式声明；测试运行等 Worker 依赖能力仍须配置可达的
 * {@code base-url} 与服务间令牌，不能把该开关误当作本地执行回退。
 */
@Data
@ConfigurationProperties(prefix = "app.worker")
public class SandboxWorkerProperties {

    /**
     * Worker 服务根地址，例如 http://localhost:8091。
     */
    private String baseUrl = "http://localhost:8091";

    /**
     * Shared secret used for authenticated calls from the main backend to Worker.
     */
    private String backendServiceToken = "";

    /**
     * 是否在部署配置中启用 Worker 集成。远程 Worker 场景必须设置为 true。
     */
    private boolean enabled = false;

    /**
     * 创建 Sandbox 使用的镜像配置名，必须命中 Worker 白名单（默认 dev-tools）。
     */
    private String imageProfile = "dev-tools";

    /**
     * 「同步 Git Store + 准备 Workspace」阶段的最大尝试次数（含首次）。
     * 初始化阶段的瞬态失败（Worker 不可达、远程仓库未同步等）在此上限内自动重试，
     * 耗尽后才进入 Task 的 failStartup 失败链路。
     */
    private int acquireMaxAttempts = 3;

    /**
     * 初始化阶段重试的初始退避：第 n 次重试前等待 initial * 2^(n-1)。
     */
    private Duration acquireInitialBackoff = Duration.ofSeconds(1);

    /**
     * 轮询工具执行结果的间隔。
     */
    private Duration pollInterval = Duration.ofMillis(250);

    /**
     * 等待一次工具执行进入终态的最大时长。
     */
    private Duration pollTimeout = Duration.ofMinutes(15);

    /**
     * 定时续租 Sandbox 空闲租约的间隔。
     */
    private Duration leaseRenewInterval = Duration.ofSeconds(10);

    /**
     * 连接超时（建立 TCP/HTTP 连接的最长等待）。
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 响应超时（请求发出到收到响应的最长等待）。Worker 请求挂起时在此期限内转为
     * {@code SANDBOX_WORKER_UNAVAILABLE}，避免长时间占住编排线程。
     */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * 续租频率必须为正数；零或负数会令轮询循环持续调用 Worker，造成请求风暴。
     */
    @PostConstruct
    void validateLeaseRenewInterval() {
        leaseRenewInterval();
        acquireMaxAttempts();
        acquireInitialBackoff();
    }

    /**
     * 返回已校验的续租间隔，供非 Spring 单元测试和运行时调用共同使用。
     */
    public Duration leaseRenewInterval() {
        if (leaseRenewInterval == null || leaseRenewInterval.isZero() || leaseRenewInterval.isNegative()) {
            throw new IllegalStateException("app.worker.lease-renew-interval must be greater than zero");
        }
        return leaseRenewInterval;
    }

    /**
     * 返回已校验的初始化最大尝试次数，包含首次调用。
     */
    public int acquireMaxAttempts() {
        if (acquireMaxAttempts < 1) {
            throw new IllegalStateException("app.worker.acquire-max-attempts must be at least one");
        }
        return acquireMaxAttempts;
    }

    /**
     * 返回已校验的初始化重试初始退避时间。
     */
    public Duration acquireInitialBackoff() {
        if (acquireInitialBackoff == null || acquireInitialBackoff.isNegative()) {
            throw new IllegalStateException("app.worker.acquire-initial-backoff must not be negative");
        }
        return acquireInitialBackoff;
    }
}
