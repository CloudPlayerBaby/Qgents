package qg.qgent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一性能指标入口。标签只使用有限枚举，资源 ID 仍写入结构化日志，避免高基数指标拖垮监控系统。
 */
@Component
public class PerformanceMetrics {
    private final MeterRegistry registry;

    public PerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void stop(Timer.Sample sample, String metric, String operation, String outcome) {
        if (sample != null) {
            sample.stop(Timer.builder(metric)
                    .tag("operation", safe(operation))
                    .tag("outcome", safe(outcome))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
    }

    public void recordDuration(String metric, long durationNanos, String operation, String outcome) {
        Timer.builder(metric)
                .tag("operation", safe(operation))
                .tag("outcome", safe(outcome))
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void increment(String metric, String operation, String outcome) {
        Counter.builder(metric)
                .tag("operation", safe(operation))
                .tag("outcome", safe(outcome))
                .register(registry)
                .increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
