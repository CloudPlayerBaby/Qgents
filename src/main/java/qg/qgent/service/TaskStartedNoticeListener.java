package qg.qgent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.dto.Mention;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.UserMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 手动发起任务后的群聊提示：任务创建事务提交后，以编排助手身份向需求群插入一句
 * 「@发起者 您创建的任务已开始」文本消息（不新增消息类型，复用 TEXT）。
 * <p>
 * 仅在任务由用户手动发起（{@code triggerMessageId} 为空，非 @agent 自动触发 / 显式
 * 消息触发）时插入，避免与 @agent 消息本身重复提示。异步 AFTER_COMMIT 执行，不占
 * 任务创建事务与群行锁；发送失败只记日志，不阻断任务。编排助手缺失时跳过并告警，
 * 不降级为 SYSTEM（系统消息通道只承接 DIFF/TASK_STATUS 卡片）。
 */
@Component
public class TaskStartedNoticeListener {
    private static final Logger log = LoggerFactory.getLogger(TaskStartedNoticeListener.class);

    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final MessageService messageService;
    private final OrchestratorAgentService orchestratorAgents;

    public TaskStartedNoticeListener(TaskMapper taskMapper, UserMapper userMapper,
                                     MessageService messageService,
                                     OrchestratorAgentService orchestratorAgents) {
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
        this.messageService = messageService;
        this.orchestratorAgents = orchestratorAgents;
    }

    /**
     * 任务创建提交后异步插入「任务已开始」提示；手动发起（无触发消息）才插入。
     */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        TaskEntity task = taskMapper.selectById(event.taskId());
        if (task == null || task.getTriggerMessageId() != null) {
            return;
        }
        UUID agentId = orchestratorAgents.resolveIdForTask(task);
        if (agentId == null) {
            log.warn("task started notice skipped, orchestrator agent missing taskId={}", task.getId());
            return;
        }
        String creatorName = creatorDisplayName(task.getCreatedBy());
        MessageSendRequest body = new MessageSendRequest();
        body.setType("TEXT");
        body.setContent(Map.of("text", "@" + creatorName + " 您创建的任务已开始"));
        Mention mention = new Mention();
        mention.setType("USER");
        mention.setId(task.getCreatedBy());
        body.setMentions(List.of(mention));
        // 固定 clientMessageId：同一任务重复触发只插入一次
        body.setClientMessageId("task-started-" + task.getId());
        try {
            messageService.sendAsAgent(task.getRequirementGroupId(), agentId, body);
            log.info("task started notice inserted taskId={} groupId={}", task.getId(),
                    task.getRequirementGroupId());
        } catch (RuntimeException e) {
            log.warn("task started notice skipped taskId={}: {}", task.getId(), e.getMessage());
        }
    }

    private String creatorDisplayName(UUID userId) {
        if (userId == null) {
            return "成员";
        }
        try {
            UserEntity creator = userMapper.selectById(userId);
            return creator == null || creator.getDisplayName() == null || creator.getDisplayName().isBlank()
                    ? "成员" : creator.getDisplayName();
        } catch (RuntimeException e) {
            return "成员";
        }
    }
}
