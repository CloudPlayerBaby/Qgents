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
 * {@code enabled=false}（默认）时编排链路仍走本地 {@code Local*} 端口实现，现状不变；
 * {@code enabled=true} 时由本包内的 Worker 端口实现替代。
 */
@Data
@ConfigurationProperties(prefix = "app.worker")
public class SandboxWorkerProperties {

    /**
     * Worker 服务根地址，例如 http://localhost:8091。
     */
    private String baseUrl = "http://localhost:8091";

    /**
     * 是否启用 Worker 端口实现；false 时保留本地端口，true 时改走 Worker HTTP API。
     */
    private boolean enabled = false;

    /**
     * 创建 Sandbox 使用的镜像配置名，必须命中 Worker 白名单（默认 java-node）。
     */
    private String imageProfile = "java-node";

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
     * 续租频率必须为正数；零或负数会令轮询循环持续调用 Worker，造成请求风暴。
     */
    @PostConstruct
    void validateLeaseRenewInterval() {
        leaseRenewInterval();
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
}
