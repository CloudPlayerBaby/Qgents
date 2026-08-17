package qg.qgent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 为 Task 编排提供独立的异步执行器，避免与测试执行器和定时调度器混用。
 */
@Configuration
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
}
