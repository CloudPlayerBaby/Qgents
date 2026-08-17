package qg.qgent.service;

import java.util.UUID;

/**
 * 任务续跑请求事件（Spring ApplicationEvent）。
 * <p>
 * 由任务运行域 {@link TaskRunService#retry} 在重试受理后发布；后端1 的编排触发监听器
 * （{@code qg.qgent.orchestration.TaskResumeListener}）在事务提交后异步从指定步骤续跑编排。
 * 区别于 {@link EventService} 的 SSE 项目级事件（面向前端刷新），本事件用于进程内触发后端逻辑。
 *
 * @param projectId        任务所属项目 ID。
 * @param taskId           要续跑的 Task ID。
 * @param startStepId      起始步骤 ID（从该步骤开始续跑）。
 * @param retryOfTaskRunId 续跑来源的运行 ID（用户重试的那个 FAILED/CANCELLED/BLOCKED run）；
 *                         续跑产生的首个 TaskRun 以 {@code retryOfTaskRunId} 指向它，审计链可追溯。
 *                         崩溃恢复（无源 run）时为 null。
 */
public record TaskResumeRequestedEvent(UUID projectId, UUID taskId, UUID startStepId, UUID retryOfTaskRunId) {

}
