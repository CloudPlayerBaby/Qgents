package qg.qgent.service.event;

import java.util.UUID;

/**
 * delivery.started 的进程内领域事件（事务提交后发布）。
 * <p>
 * 与 SSE 事件 {@code delivery.started} 同由 FinalDiffBundleService 在业务事务内发布，
 * 前者面向浏览器展示，本事件面向主后端内部的交付模块（{@code @TransactionalEventListener(AFTER_COMMIT)}），
 * 用于毫秒级唤起 MR_FIRST 交付执行器。交付模块不得反向消费浏览器 SSE 作为消息队列。
 * <p>
 * payload 仅携带脱敏元数据，不含代码内容与凭证。
 */
public record DeliveryStartedDomainEvent(UUID projectId, UUID taskId, UUID reviewBatchId, String operationId) {
}
