package qg.qgent.service.event;

import java.util.UUID;

/**
 * 一条 MR 前 Dry Run 以 FAILED 结束后的候选事件。
 * <p>
 * 事件只携带标识；监听器会重新读取 DryRunEntity 的 report，确认是否真的是确定性合并冲突
 * （MERGE_CONFLICT / GIT_MERGE_CONFLICT），瞬时或上下文失败不会派发解决冲突的续跑任务。
 */
public record DryRunConflictCandidateDomainEvent(UUID projectId, UUID dryRunId, UUID taskId) {
}
