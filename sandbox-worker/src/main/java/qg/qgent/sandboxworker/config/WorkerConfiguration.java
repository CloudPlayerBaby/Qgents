package qg.qgent.sandboxworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 工作节点公共组件配置。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SandboxWorkerProperties.class)
public class WorkerConfiguration {
    /** 提供统一 JSON 序列化/反序列化工具，避免直接使用 Jackson。 */
    @Bean
    ObjectMapper workerObjectMapper() {
        return new ObjectMapper();
    }

    /** 提供统一 UTC 时钟，便于测试替换和时间语义一致。 */
    @Bean
    Clock workerClock() {
        return Clock.systemUTC();
    }

    /** 创建兼容版异步命令使用的固定大小线程池。 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService sandboxExecutionPool(SandboxWorkerProperties properties) {
        return Executors.newFixedThreadPool(properties.getExecutionThreads(),
                Thread.ofPlatform().name("sandbox-execution-", 0).factory());
    }

    /** 创建单线程超时调度器，只负责取消到期执行。 */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService sandboxTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sandbox-timeout-", 0).factory());
    }
}
