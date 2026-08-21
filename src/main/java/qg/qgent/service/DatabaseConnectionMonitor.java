package qg.qgent.service;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 数据库连接池健康监控：周期性采样 Hikari 池的活跃/空闲/等待线程数。
 * <p>
 * 池接近上限（空闲连接 ≤ 阈值）时输出 WARN 并附当前等待线程数，便于在
 * 「连接池瞬时耗尽」（Hikari Connection is not available, request timed out）出现前
 * 定位占满连接的峰值窗口与并发来源；正常状态仅输出 INFO 采样，不产生额外数据库查询。
 * 仅支持 Hikari 数据源（Spring Boot 默认），其他实现直接跳过。
 */
@Component
public class DatabaseConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionMonitor.class);

    private final DataSource dataSource;
    private final boolean enabled;
    private final int warnWhenIdleAtMost;

    public DatabaseConnectionMonitor(DataSource dataSource,
                                     @Value("${qgents.db-pool-monitor.enabled:true}") boolean enabled,
                                     @Value("${qgents.db-pool-monitor.warn-when-idle-at-most:2}") int warnWhenIdleAtMost) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.warnWhenIdleAtMost = warnWhenIdleAtMost;
    }

    /**
     * 每分钟采样一次连接池状态；空闲连接数降至阈值时告警，否则仅记录采样值。
     * 采样本身不占数据库连接（读取 Hikari 内存指标）。
     */
    @Scheduled(fixedDelayString = "${qgents.db-pool-monitor.interval-ms:60000}",
            initialDelayString = "${qgents.db-pool-monitor.initial-delay-ms:60000}")
    public void samplePool() {
        if (!enabled || !(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        int max = hikari.getMaximumPoolSize();
        int active = hikari.getHikariPoolMXBean().getActiveConnections();
        int idle = hikari.getHikariPoolMXBean().getIdleConnections();
        int waiting = hikari.getHikariPoolMXBean().getThreadsAwaitingConnection();
        if (idle <= warnWhenIdleAtMost) {
            log.warn("db connection pool near capacity active={} idle={} waiting={} max={}",
                    active, idle, waiting, max);
        } else {
            log.info("db connection pool sample active={} idle={} waiting={} max={}",
                    active, idle, waiting, max);
        }
    }
}
