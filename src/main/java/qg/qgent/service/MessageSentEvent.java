package qg.qgent.service;

import qg.qgent.dto.Mention;

import java.util.List;
import java.util.UUID;

/**
 * 消息发送完成事件（Spring ApplicationEvent）。
 * <p>
 * 消息已在发送事务内落库并提交后发布。用于把「@Agent 自动创建 Task」与「@ 用户通知」等
 * 非关键路径移出发送事务——发送响应不再等待建任务/写通知，显著降低消息发出与卡片回群的延迟；
 * 群行锁持有时间缩短，同群并发消息（用户消息 + Agent 卡片 + 系统卡片）不再互相排队阻塞。
 *
 * @param actor     发送者用户 ID
 * @param projectId 消息所属项目 ID
 * @param groupId   消息所属需求群 ID
 * @param messageId 已落库消息 ID
 * @param mentions  消息提及列表（可为空）
 */
public record MessageSentEvent(UUID actor, UUID projectId, UUID groupId, UUID messageId,
                               List<Mention> mentions) {
}
