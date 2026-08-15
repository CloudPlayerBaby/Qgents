package qg.qgent.service;

import java.util.UUID;

/**
 * 任务创建完成事件（Spring ApplicationEvent）。
 * <p>
 * 由任务域 {@link TaskService#create} 在任务及其 Workspace/仓库落库后、事务提交前发布；
 * 后端1 的编排触发监听器（{@code qg.qgent.orchestration.TaskExecutionListener}）在事务
 * 提交后异步驱动编排执行。区别于 {@link EventService} 的 SSE 项目级事件（面向前端刷新），
 * 本事件用于进程内触发后端逻辑。
 *
 * @param projectId 任务所属项目 ID。
 * @param taskId    新建任务 ID。
 */
public record TaskCreatedEvent(UUID projectId, UUID taskId) {

}
