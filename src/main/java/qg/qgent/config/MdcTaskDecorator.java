package qg.qgent.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务 MDC 装饰器：提交任务时复制提交线程的 MDC（requestId 等），
 * 执行完毕后恢复原状，避免后台线程日志丢失请求上下文或泄漏到下一个任务。
 * <p>
 * 注册方式：在 {@link LoggingConfiguration} 中声明为 Bean 后，Spring Boot
 * 自动配置的默认执行器（未显式指定执行器的 {@code @Async}）会自动应用它；
 * 显式 new 的线程池需手动 {@code setTaskDecorator(new MdcTaskDecorator())}。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
