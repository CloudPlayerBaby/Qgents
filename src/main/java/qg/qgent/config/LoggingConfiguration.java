package qg.qgent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * 日志相关配置：注册唯一的 {@link TaskDecorator} Bean，
 * Spring Boot 自动配置的默认任务执行器会拾取它，使未显式指定执行器的
 * {@code @Async} 任务（如邮件发送）同样继承提交线程的 MDC（requestId）。
 */
@Configuration
public class LoggingConfiguration {

    @Bean
    TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
