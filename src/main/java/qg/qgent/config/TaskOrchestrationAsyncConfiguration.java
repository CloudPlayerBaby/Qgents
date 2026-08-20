package qg.qgent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.support.ExecutorServiceAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import qg.qgent.orchestration.OrchestrationTimeoutProperties;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 为 Task 编排提供独立的异步执行器，避免与测试执行器和定时调度器混用。
 */
@Configuration
@EnableConfigurationProperties({OrchestrationTimeoutProperties.class, ExecutorPerformanceProperties.class})
public class TaskOrchestrationAsyncConfiguration {

    @Bean(name = "taskOrchestratorExecutor")
    public Executor taskOrchestratorExecutor(ExecutorPerformanceProperties properties,
                                              ObjectProvider<PerformanceMetrics> metricsProvider) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        ExecutorPerformanceProperties.Pool pool = properties.getOrchestration();
        pool.validate("orchestration");
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("task-orchestrator-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        bindMetrics(executor, "orchestration", metricsProvider.getIfAvailable());
        return executor;
    }

    /**
     * 超时执行器：在独立线程上运行 `agent.run()` 以便为其施加总时限（Future.get(timeout)），
     * 与编排主线程解耦；超时后旧线程尽量中断，不等待其返回。用 {@link ExecutorServiceAdapter}
     * 把 Spring 线程池适配为 JDK {@link ExecutorService}，便于 submit 并取 Future。
     */
    @Bean(name = "taskRunTimeoutExecutor")
    public ExecutorService taskRunTimeoutExecutor(ExecutorPerformanceProperties properties,
                                                   ObjectProvider<PerformanceMetrics> metricsProvider) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        ExecutorPerformanceProperties.Pool pool = properties.getTaskRunTimeout();
        pool.validate("task-run-timeout");
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("task-run-timeout-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        bindMetrics(executor, "task-run-timeout", metricsProvider.getIfAvailable());
        return new ExecutorServiceAdapter(executor);
    }

    private void bindMetrics(ThreadPoolTaskExecutor executor, String name, PerformanceMetrics metrics) {
        if (metrics == null) {
            return;
        }
        java.util.concurrent.ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.active", pool, java.util.concurrent.ThreadPoolExecutor::getActiveCount)
                .tag("executor", name).register(metricsRegistry(metrics));
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.pool.size", pool, java.util.concurrent.ThreadPoolExecutor::getPoolSize)
                .tag("executor", name).register(metricsRegistry(metrics));
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.queue.size", pool, value -> value.getQueue().size())
                .tag("executor", name).register(metricsRegistry(metrics));
    }

    private io.micrometer.core.instrument.MeterRegistry metricsRegistry(PerformanceMetrics metrics) {
        return metrics.registry();
    }
}
