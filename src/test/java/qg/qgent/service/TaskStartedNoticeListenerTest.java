package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import qg.qgent.dto.Mention;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.UserMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskStartedNoticeListener} 单测：任务创建后在需求群插入一次启动确认。
 * 直接调用监听器方法，不验证 @Async/@TransactionalEventListener 的代理行为。
 */
class TaskStartedNoticeListenerTest {

    @Test
    void usesDedicatedOrchestrationExecutor() throws NoSuchMethodException {
        Async async = TaskStartedNoticeListener.class
                .getMethod("onTaskCreated", TaskCreatedEvent.class)
                .getAnnotation(Async.class);

        assertEquals("taskOrchestratorExecutor", async.value());
    }

    @Test
    void manualTaskInsertsStartedNotice() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID(), agentId = UUID.randomUUID();
        TaskMapper tasks = mock(TaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService agents = mock(OrchestratorAgentService.class);
        TaskEntity task = task(taskId, projectId, groupId, creatorId);
        UserEntity creator = new UserEntity();
        creator.setId(creatorId);
        creator.setDisplayName("张三");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(users.selectById(creatorId)).thenReturn(creator);
        when(agents.resolveIdForTask(task)).thenReturn(agentId);

        TaskStartedNoticeListener listener = new TaskStartedNoticeListener(tasks, users, messages, agents);
        listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId));

        verify(messages).sendAsAgent(any(), any(), any());
    }

    @Test
    void messageCarriesCreatorMentionAndText() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID(), agentId = UUID.randomUUID();
        TaskMapper tasks = mock(TaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService agents = mock(OrchestratorAgentService.class);
        TaskEntity task = task(taskId, projectId, groupId, creatorId);
        task.setDisplayCode("T-42");
        UserEntity creator = new UserEntity();
        creator.setId(creatorId);
        creator.setDisplayName("张三");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(users.selectById(creatorId)).thenReturn(creator);
        when(agents.resolveIdForTask(task)).thenReturn(agentId);

        TaskStartedNoticeListener listener = new TaskStartedNoticeListener(tasks, users, messages, agents);
        listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId));

        ArgumentCaptor<MessageSendRequest> body = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(messages).sendAsAgent(org.mockito.ArgumentMatchers.eq(groupId),
                org.mockito.ArgumentMatchers.eq(agentId), body.capture());
        MessageSendRequest captured = body.getValue();
        assertEquals("TEXT", captured.getType());
        assertEquals("task-started-" + taskId, captured.getClientMessageId());
        String text = (String) ((Map<?, ?>) captured.getContent()).get("text");
        assertTrue(text.startsWith("@张三"));
        assertTrue(text.contains("已收到你的需求"));
        assertTrue(text.contains("任务 T-42 已开始规划"));
        assertEquals(1, captured.getMentions().size());
        Mention mention = captured.getMentions().getFirst();
        assertEquals("USER", mention.getType());
        assertEquals(creatorId, mention.getId());
    }

    @Test
    void agentTriggeredTaskAlsoInsertsStartedNotice() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID(), agentId = UUID.randomUUID();
        TaskMapper tasks = mock(TaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService agents = mock(OrchestratorAgentService.class);
        TaskEntity task = task(taskId, projectId, groupId, creatorId);
        task.setTriggerMessageId(UUID.randomUUID());
        when(agents.resolveIdForTask(task)).thenReturn(agentId);
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskStartedNoticeListener listener = new TaskStartedNoticeListener(tasks, users, messages, agents);
        listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId));

        verify(messages).sendAsAgent(any(), any(), any());
    }

    @Test
    void missingOrchestratorAgentSkipsNotice() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        TaskMapper tasks = mock(TaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService agents = mock(OrchestratorAgentService.class);
        TaskEntity task = task(taskId, projectId, groupId, creatorId);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(agents.resolveIdForTask(task)).thenReturn(null);

        TaskStartedNoticeListener listener = new TaskStartedNoticeListener(tasks, users, messages, agents);
        listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId));

        verify(messages, never()).sendAsAgent(any(), any(), any());
    }

    @Test
    void sendFailureDoesNotPropagate() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID(), agentId = UUID.randomUUID();
        TaskMapper tasks = mock(TaskMapper.class);
        UserMapper users = mock(UserMapper.class);
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService agents = mock(OrchestratorAgentService.class);
        TaskEntity task = task(taskId, projectId, groupId, creatorId);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(users.selectById(creatorId)).thenReturn(null);
        when(agents.resolveIdForTask(task)).thenReturn(agentId);
        doThrow(new RuntimeException("group locked")).when(messages).sendAsAgent(any(), any(), any());

        TaskStartedNoticeListener listener = new TaskStartedNoticeListener(tasks, users, messages, agents);

        assertDoesNotThrow(() -> listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId)));
    }

    private TaskEntity task(UUID id, UUID projectId, UUID groupId, UUID createdBy) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProjectId(projectId);
        task.setRequirementGroupId(groupId);
        task.setCreatedBy(createdBy);
        return task;
    }
}
