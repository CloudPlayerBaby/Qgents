package qg.qgent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ExecutorServiceAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import qg.qgent.orchestration.OrchestrationTimeoutProperties;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 为 Task 编排提供独立的异步执行器，避免与测试执行器和定时调度器混用。
 */
@Configuration
@EnableConfigurationProperties(OrchestrationTimeoutProperties.class)
public class TaskOrchestrationAsyncConfiguration {

    @Bean(name = "taskOrchestratorExecutor")
    public Executor taskOrchestratorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("task-orchestrator-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 超时执行器：在独立线程上运行 `agent.run()` 以便为其施加总时限（Future.get(timeout)），
     * 与编排主线程解耦；超时后旧线程尽量中断，不等待其返回。用 {@link ExecutorServiceAdapter}
     * 把 Spring 线程池适配为 JDK {@link ExecutorService}，便于 submit 并取 Future。
     */
    @Bean(name = "taskRunTimeoutExecutor")
    public ExecutorService taskRunTimeoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("task-run-timeout-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return new ExecutorServiceAdapter(executor);
    }
}
