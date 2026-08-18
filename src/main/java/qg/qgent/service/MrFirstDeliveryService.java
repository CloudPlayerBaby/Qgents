package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.service.event.DeliveryStartedDomainEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * MR_FIRST 交付执行器（后端3）：消费 {@link DeliveryStartedDomainEvent} 与兜底扫描，
 * 驱动系统授权批次的逐仓库交付（commit → push → 状态回写），随后等待 MR 前预检。
 * <p>
 * 双通道触发，幂等由批次租约保证：
 * <ul>
 *   <li>实时主路径：事务提交后监听 {@code DeliveryStartedDomainEvent}，毫秒级唤起交付；</li>
 *   <li>恢复路径：定时扫描 {@code confirmationSource=SYSTEM} 且租约过期的 DELIVERING 批次，
 *       补偿事件丢失、监听异常或主后端重启；重新领取（换新 claimToken）后继续执行。</li>
 * </ul>
 * 本类不把浏览器 SSE 当作消息队列；SSE 仅面向前端展示。执行失败不抛出到监听器
 * （避免中断其他监听器），失败事实由批次/仓库级 FAILED 状态与稳定失败码承载，
 * 用户可经 retry-delivery 重试失败仓库。
 */
@Service
@Slf4j
public class MrFirstDeliveryService {
    private final DiffReviewBatchMapper batches;
    private final TaskMapper tasks;
    private final DiffReviewBatchService diffReviewBatches;
    private final TransactionTemplate transactions;

    public MrFirstDeliveryService(DiffReviewBatchMapper batches, TaskMapper tasks,
                                  DiffReviewBatchService diffReviewBatches, TransactionTemplate transactions) {
        this.batches = batches;
        this.tasks = tasks;
        this.diffReviewBatches = diffReviewBatches;
        this.transactions = transactions;
    }

    /**
     * 实时主路径：delivery.started 领域事件在事务提交后触发。
     * 异常只记日志——兜底扫描会重新领取，事件不允许重复消费产生副作用（租约幂等）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryStarted(DeliveryStartedDomainEvent event) {
        try {
            deliver(event.projectId(), event.taskId(), event.reviewBatchId(), event.operationId());
        } catch (RuntimeException failure) {
            log.error("mr-first event delivery aborted projectId={} taskId={} operationId={}: {}",
                    event.projectId(), event.taskId(), event.operationId(), failure.getMessage(), failure);
        }
    }

    /**
     * 恢复路径：扫描租约过期的 SYSTEM 授权 DELIVERING 批次，重新领取后继续交付。
     * 只处理租约过期的批次；活跃租约（事件通道正在执行）跳过，避免重复交付。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void recoverStuckDeliveries() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<DiffReviewBatchEntity> stuck = batches.selectList(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getConfirmationSource, "SYSTEM")
                .eq(DiffReviewBatchEntity::getDeliveryStatus, "DELIVERING")
                .isNotNull(DiffReviewBatchEntity::getDeliveryLeaseExpiresAt)
                .le(DiffReviewBatchEntity::getDeliveryLeaseExpiresAt, now)
                .last("LIMIT 20"));
        for (DiffReviewBatchEntity batch : stuck) {
            try {
                reclaim(batch);
            } catch (RuntimeException failure) {
                log.error("mr-first recovery failed projectId={} taskId={} reviewBatchId={}: {}",
                        batch.getProjectId(), batch.getTaskId(), batch.getId(), failure.getMessage(), failure);
            }
        }
    }

    /**
     * 重新领取过期租约（换新 claimToken），成功后驱动交付。
     */
    private void reclaim(DiffReviewBatchEntity stale) {
        DiffReviewBatchEntity reclaimed = transactions.execute(status -> {
            DiffReviewBatchEntity locked = batches.selectByIdForUpdate(stale.getId());
            if (locked == null || !"SYSTEM".equals(locked.getConfirmationSource())
                    || !"DELIVERING".equals(locked.getDeliveryStatus())
                    || locked.getDeliveryLeaseExpiresAt() == null
                    || locked.getDeliveryLeaseExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            locked.setDeliveryClaimToken(UUID.randomUUID().toString());
            locked.setDeliveryLeaseExpiresAt(now.plus(DiffReviewBatchService.DELIVERY_LEASE));
            locked.setUpdatedAt(now);
            batches.updateById(locked);
            return locked;
        });
        if (reclaimed != null) {
            log.info("mr-first delivery reclaimed projectId={} taskId={} reviewBatchId={} operationId={}",
                    reclaimed.getProjectId(), reclaimed.getTaskId(), reclaimed.getId(),
                    reclaimed.getDeliveryOperationId());
            deliver(reclaimed.getProjectId(), reclaimed.getTaskId(), reclaimed.getId(),
                    reclaimed.getDeliveryOperationId());
        }
    }

    /**
     * 校验任务与批次上下文后委托既有交付链路。租约不匹配（已被其他通道领取或已完成）时静默跳过。
     */
    private void deliver(UUID projectId, UUID taskId, UUID reviewBatchId, String operationId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            log.warn("mr-first delivery skipped, task not found projectId={} taskId={}", projectId, taskId);
            return;
        }
        if (!"DELIVERING".equals(task.getStatus()) || !"MR_FIRST".equals(task.getDeliveryMode())) {
            log.info("mr-first delivery skipped, task not deliverable projectId={} taskId={} status={}",
                    projectId, taskId, task == null ? null : task.getStatus());
            return;
        }
        DiffReviewBatchEntity batch = batches.selectById(reviewBatchId);
        if (batch == null || !operationId.equals(batch.getDeliveryOperationId())
                || !taskId.equals(batch.getTaskId())) {
            log.warn("mr-first delivery skipped, batch/operation mismatch projectId={} taskId={} reviewBatchId={}",
                    projectId, taskId, reviewBatchId);
            return;
        }
        String claimToken = batch.getDeliveryClaimToken();
        try {
            diffReviewBatches.deliverSystemAcceptedBatch(projectId, taskId, reviewBatchId, claimToken);
        } catch (RuntimeException failure) {
            // 监听器会吞掉异常以免影响其他 after-commit 监听器。先交还当前 token，避免
            // Worker 瞬态故障或 Workspace 写租约竞争把任务无操作地卡在 DELIVERING 半小时。
            diffReviewBatches.relinquishSystemDeliveryClaim(projectId, taskId, reviewBatchId, claimToken);
            throw failure;
        }
    }
}
