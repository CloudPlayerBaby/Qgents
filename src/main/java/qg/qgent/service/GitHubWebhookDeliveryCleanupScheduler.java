package qg.qgent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import qg.qgent.mapper.GitHubWebhookDeliveryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 清理超过保留窗口的 GitHub Webhook 投递记录。
 * 保留窗口 30 天；FAILED 记录至少保留同一窗口，便于联调和排障。
 * RECEIVED（处理中或中断）不清理。
 */
@Component
@Slf4j
public class GitHubWebhookDeliveryCleanupScheduler {
    /**
     * 投递记录保留天数。
     */
    private static final int RETENTION_DAYS = 30;

    private final GitHubWebhookDeliveryMapper deliveryMapper;

    public GitHubWebhookDeliveryCleanupScheduler(GitHubWebhookDeliveryMapper deliveryMapper) {
        this.deliveryMapper = deliveryMapper;
    }

    /**
     * 每日凌晨清理 30 天前的已完成投递记录。
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void purgeExpired() {
        try {
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
            int removed = deliveryMapper.deleteCompletedBefore(before);
            if (removed > 0) {
                log.info("purged {} expired GitHub webhook deliveries before {}", removed, before);
            }
        } catch (Exception e) {
            log.warn("github webhook delivery purge failed: {}", e.getMessage());
        }
    }
}
