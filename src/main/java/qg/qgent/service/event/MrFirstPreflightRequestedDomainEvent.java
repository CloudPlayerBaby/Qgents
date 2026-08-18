package qg.qgent.service.event;

import java.util.UUID;

/**
 * MR_FIRST 代码已完成 commit/push、可以开始预检的进程内事件。
 * 事件只携带任务标识；Dry Run 的源提交、目标分支和 Testset 由服务端重新读取，
 * 防止事件载荷被当成代码交付事实。
 */
public record MrFirstPreflightRequestedDomainEvent(UUID projectId, UUID taskId) {
}
