package qg.qgent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 为可恢复 TestRun/DryRun 提供有界且命名明确的异步执行器。
 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(ExecutorPerformanceProperties.class)
public class TestExecutionAsyncConfiguration {
    @Bean(name = "testExecutionExecutor")
    public Executor testExecutionExecutor(ExecutorPerformanceProperties properties,
                                          ObjectProvider<PerformanceMetrics> metricsProvider) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        ExecutorPerformanceProperties.Pool pool = properties.getTestExecution();
        pool.validate("test-execution");
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("test-execution-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        PerformanceMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics == null) {
            return executor;
        }
        java.util.concurrent.ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
        io.micrometer.core.instrument.MeterRegistry registry = metricsRegistry(metrics);
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.active", threadPool,
                        java.util.concurrent.ThreadPoolExecutor::getActiveCount)
                .tag("executor", "test-execution").register(registry);
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.pool.size", threadPool,
                        java.util.concurrent.ThreadPoolExecutor::getPoolSize)
                .tag("executor", "test-execution").register(registry);
        io.micrometer.core.instrument.Gauge.builder("qgents.executor.queue.size", threadPool,
                        value -> value.getQueue().size())
                .tag("executor", "test-execution").register(registry);
        return executor;
    }

    private io.micrometer.core.instrument.MeterRegistry metricsRegistry(PerformanceMetrics metrics) {
        return metrics.registry();
    }
}
