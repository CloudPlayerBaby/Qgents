package qg.qgent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.dto.Mention;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;

import java.util.List;
import java.util.UUID;

/**
 * 消息发送提交后的异步收尾：@Agent 自动建任务 + @ 用户站内通知。
 * <p>
 * 消息落库并提交（{@code AFTER_COMMIT}）后才执行，避免与发送事务争用群行锁；
 * 异步线程（默认执行器）执行，消息发送 HTTP 响应不再等待建任务/写通知。
 * <p>
 * 失败语义：任务触发失败只记日志（幂等由 TaskTriggerService 保证，同消息只建一次）；
 * 通知失败同样不阻断（原本就是 try-catch 兜底）。事件监听在事务提交后触发，
 * 消息已持久化，重试/并发安全由下游幂等约束保证。
 */
@Component
public class MessageSentListener {
    private static final Logger log = LoggerFactory.getLogger(MessageSentListener.class);

    private final MessageMapper messageMapper;
    private final RequirementGroupMapper groupMapper;
    private final TaskTriggerService taskTriggerService;
    private final MessageService messageService;

    public MessageSentListener(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
                               TaskTriggerService taskTriggerService, MessageService messageService) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.taskTriggerService = taskTriggerService;
        this.messageService = messageService;
    }

    /**
     * 消息提交后异步：先建 Task（@Agent），再补发 @ 用户通知。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        UUID messageId = event.messageId();
        List<Mention> mentions = event.mentions();
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        try {
            MessageEntity message = messageMapper.selectById(messageId);
            boolean hasAgent = mentions.stream().anyMatch(m -> "AGENT".equals(m.getType()));
            boolean hasUser = mentions.stream().anyMatch(m -> "USER".equals(m.getType()));
            if (hasAgent && message != null) {
                taskTriggerService.triggerFromMention(event.actor(), event.projectId(), event.groupId(),
                        message, mentions);
            }
            if (hasUser) {
                RequirementGroupEntity group = groupMapper.selectById(event.groupId());
                messageService.notifyMentionedUsersAfterCommit(event,
                        group == null ? "群聊" : group.getName(),
                        messageService.extractTextPreview(message));
            }
        } catch (RuntimeException e) {
            log.warn("message sent post-processing skipped, messageId={}: {}", messageId, e.getMessage());
        }
    }
}
