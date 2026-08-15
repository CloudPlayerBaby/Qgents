package qg.qgent.sandboxworker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定期取消过期执行并回收沙箱，作为控制层主动销毁之外的安全兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxCleanupService {
    private final SandboxService sandboxes;
    private final ToolExecutionService executions;

    /**
     * 回收达到空闲期限或最大寿命的沙箱
     * 先取消该沙箱中的活动执行，再销毁底层容器；不会删除持久 Workspace
     */
    @Scheduled(fixedDelayString = "${sandbox.cleanup-interval:30s}")
    public void cleanupExpiredSandboxes() {
        sandboxes.expiredSandboxIds().forEach(sandboxId -> {
            executions.cancelBySandbox(sandboxId);
            sandboxes.destroy(sandboxId);
            log.info("sandbox reclaimed by scheduled cleanup sandboxId={}", sandboxId);
        });
    }
}
