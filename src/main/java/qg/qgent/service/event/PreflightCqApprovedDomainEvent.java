package qg.qgent.service.event;

import java.util.UUID;

/**
 * 独立成员通过一条 MR 前 Dry Run CQ+1 后的进程内事件。
 * 监听器负责幂等地尝试创建对应仓库的真实 MR。
 */
public record PreflightCqApprovedDomainEvent(UUID projectId, UUID dryRunId) {
}
