package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.api.ApiException;
import qg.qgent.dto.Mention;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.dto.TaskResponse;
import qg.qgent.dto.TaskTriggerRequest;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 点7：从群消息触发 Task 的转换服务测试（组装、@agent 自动触发、缺仓库跳过、幂等）。 */
class TaskTriggerServiceTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final RequirementGroupRepositoryMapper groupRepos = mock(RequirementGroupRepositoryMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskTriggerService service = new TaskTriggerService(messages, groups, groupRepos, tasks, diffs,
            taskService, groupService, access, mapper);

    @Test
    void triggerAssemblesRequestFromMessageAndGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"实现邮箱登录\"}");
        RequirementGroupEntity group = group(groupId, projectId, "REQUIREMENT", "ACTIVE");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group);
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoId));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("实现邮箱登录");
        service.trigger(actor, projectId, groupId, messageId, body);

        verify(taskService).create(eq(projectId), eq(actor), any());
    }

    @Test
    void triggerWithoutRepositoriesThrowsWhenGroupHasNoRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"实现邮箱登录\"}");
        RequirementGroupEntity group = group(groupId, projectId, "REQUIREMENT", "ACTIVE");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group);
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of());

        // 请求未传 repositoryIds，需求群也未绑仓库：必须给前端独立错误码指引绑定仓库，
        // 不得静默回退到项目全部仓库或落到模糊的 TASK_REPOSITORY_REQUIRED。
        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("实现邮箱登录");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, messageId, body));

        assertEquals("REQUIREMENT_GROUP_NO_REPOSITORIES", error.code());
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerFallsBackToGroupRepositoriesWhenRepositoryIdsOmitted() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoA = UUID.randomUUID(), repoB = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"实现邮箱登录\"}");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoA, repoB));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("实现邮箱登录");
        body.setBaseRef("main");
        service.trigger(actor, projectId, groupId, messageId, body);

        TaskCreateRequest request = capturedCreateRequest(projectId, actor);
        assertEquals(List.of(repoA, repoB), request.getRepositoryIds());
        assertEquals("main", request.getBaseRef());
    }

    @Test
    void triggerRejectsMessageNotInGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), otherGroup = UUID.randomUUID();
        when(messages.selectById(messageId)).thenReturn(message(otherGroup, messageId, "{\"text\":\"x\"}"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        assertThrows(ApiException.class, () -> service.trigger(actor, projectId, groupId, messageId, body));
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerFromMentionSkipsWhenNoAgentMention() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        MessageEntity message = message(groupId, UUID.randomUUID(), "{\"text\":\"hello\"}");
        List<Mention> mentions = List.of(mention("USER"));

        TaskResponse result = service.triggerFromMention(actor, projectId, groupId, message, mentions);

        assertNull(result);
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerFromMentionSkipsWhenGroupHasNoRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        MessageEntity message = message(groupId, UUID.randomUUID(), "{\"text\":\"@agent 帮我做登录\"}");
        List<Mention> mentions = List.of(mention("AGENT"));
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of());

        TaskResponse result = service.triggerFromMention(actor, projectId, groupId, message, mentions);

        assertNull(result);
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerFromMentionSkipsWhenAlreadyTriggered() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"@agent 做登录\"}");
        List<Mention> mentions = List.of(mention("AGENT"));
        when(tasks.selectCount(any())).thenReturn(1L);

        TaskResponse result = service.triggerFromMention(actor, projectId, groupId, message, mentions);

        assertNull(result);
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerFromMentionCreatesTaskWhenAgentMentioned() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"@agent 实现登录功能\"}");
        List<Mention> mentions = List.of(mention("AGENT"));
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoId));

        service.triggerFromMention(actor, projectId, groupId, message, mentions);

        verify(taskService).create(eq(projectId), eq(actor), any());
    }

    @Test
    void triggerFromMentionRejectsMessageNotInGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID();
        MessageEntity message = message(otherGroup, UUID.randomUUID(), "{\"text\":\"@agent x\"}");
        List<Mention> mentions = List.of(mention("AGENT"));

        assertThrows(ApiException.class,
                () -> service.triggerFromMention(actor, projectId, groupId, message, mentions));
    }

    @Test
    void triggerWithQuotedDiffReusesWorkspace() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID();
        UUID diffId = UUID.randomUUID(), sourceTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"按这个 Diff 继续改\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        DiffEntity diff = diff(diffId, projectId, sourceTaskId, workspaceId);
        TaskEntity sourceTask = task(sourceTaskId, projectId, groupId, workspaceId);
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(diff);
        when(tasks.selectById(sourceTaskId)).thenReturn(sourceTask);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("按 Diff 继续改");
        service.trigger(actor, projectId, groupId, currentMessageId, body);

        TaskCreateRequest request = capturedCreateRequest(projectId, actor);
        assertEquals(workspaceId, request.getWorkspaceId());
        assertEquals(sourceTaskId, request.getContinuationOfTaskId());
        assertNull(request.getRepositoryIds());
        assertEquals(currentMessageId, request.getTriggerMessageId());
    }

    @Test
    void triggerFromMentionWithQuotedDiffReusesWorkspace() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID();
        UUID diffId = UUID.randomUUID(), sourceTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"@agent 按这个 Diff 继续改\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        DiffEntity diff = diff(diffId, projectId, sourceTaskId, workspaceId);
        TaskEntity sourceTask = task(sourceTaskId, projectId, groupId, workspaceId);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(diff);
        when(tasks.selectById(sourceTaskId)).thenReturn(sourceTask);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of());

        service.triggerFromMention(actor, projectId, groupId, current, List.of(mention("AGENT")));

        TaskCreateRequest request = capturedCreateRequest(projectId, actor);
        assertEquals(workspaceId, request.getWorkspaceId());
        assertEquals(sourceTaskId, request.getContinuationOfTaskId());
        assertNull(request.getRepositoryIds());
        assertEquals(currentMessageId, request.getTriggerMessageId());
    }

    @Test
    void triggerQuotingNonDiffMessageStillCreatesNewWorkspace() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"引用讨论继续\"}", parentMessageId, "TEXT");
        MessageEntity parent = message(groupId, parentMessageId, "{\"text\":\"昨天讨论过\"}");
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("继续讨论");
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoId));
        service.trigger(actor, projectId, groupId, currentMessageId, body);

        TaskCreateRequest request = capturedCreateRequest(projectId, actor);
        assertNull(request.getWorkspaceId());
        assertNull(request.getContinuationOfTaskId());
        assertEquals(List.of(repoId), request.getRepositoryIds());
    }

    @Test
    void triggerRejectsQuotedDiffWithoutDiffId() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"x\"}", parentMessageId, "TEXT");
        MessageEntity parent = message(groupId, parentMessageId, "{\"text\":\"缺少 diffId\"}", null, "DIFF");
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, currentMessageId, body));
        assertEquals("QUOTED_DIFF_INVALID", error.code());
        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void triggerRejectsQuotedDiffWhenDiffMissing() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID(), diffId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"x\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(null);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, currentMessageId, body));
        assertEquals("QUOTED_DIFF_NOT_ACCESSIBLE", error.code());
    }

    @Test
    void triggerRejectsQuotedDiffFromAnotherProject() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID(), currentMessageId = UUID.randomUUID();
        UUID parentMessageId = UUID.randomUUID(), diffId = UUID.randomUUID(), sourceTaskId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"x\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(diff(diffId, otherProject, sourceTaskId, UUID.randomUUID()));
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, currentMessageId, body));
        assertEquals("QUOTED_DIFF_NOT_ACCESSIBLE", error.code());
    }

    @Test
    void triggerRejectsQuotedDiffFromAnotherGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID(), currentMessageId = UUID.randomUUID();
        UUID parentMessageId = UUID.randomUUID(), diffId = UUID.randomUUID(), sourceTaskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"x\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(diff(diffId, projectId, sourceTaskId, workspaceId));
        when(tasks.selectById(sourceTaskId)).thenReturn(task(sourceTaskId, projectId, otherGroup, workspaceId));
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, currentMessageId, body));
        assertEquals("QUOTED_DIFF_NOT_ACCESSIBLE", error.code());
    }

    @Test
    void triggerRejectsQuotedDiffWhenSourceTaskMissing() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID(), parentMessageId = UUID.randomUUID();
        UUID diffId = UUID.randomUUID(), sourceTaskId = UUID.randomUUID();
        MessageEntity current = message(groupId, currentMessageId, "{\"text\":\"x\"}", parentMessageId, "TEXT");
        MessageEntity parent = diffMessage(groupId, parentMessageId, diffId);
        when(messages.selectById(currentMessageId)).thenReturn(current);
        when(messages.selectById(parentMessageId)).thenReturn(parent);
        when(diffs.selectById(diffId)).thenReturn(diff(diffId, projectId, sourceTaskId, UUID.randomUUID()));
        when(tasks.selectById(sourceTaskId)).thenReturn(null);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        ApiException error = assertThrows(ApiException.class,
                () -> service.trigger(actor, projectId, groupId, currentMessageId, body));
        assertEquals("QUOTED_DIFF_NOT_ACCESSIBLE", error.code());
    }

    @Test
    void triggerReturnsExistingTaskWhenAlreadyTriggered() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"@agent 做登录\"}");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskResponse existing = mock(TaskResponse.class);
        when(taskService.findByTriggerMessage(projectId, messageId, actor)).thenReturn(existing);

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        TaskResponse result = service.trigger(actor, projectId, groupId, messageId, body);

        assertSame(existing, result);
        verify(taskService, never()).create(any(), any(), any());
    }

    /** 并发兜底：唯一约束冲突（同消息被并发建 Task）时返回已有任务，不再次创建。 */
    @Test
    void triggerConcurrentDuplicateKeyReturnsExistingTask() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"@agent 做登录\"}");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoId));
        // 并发另一请求先插入成功：本请求 create 抛唯一键冲突，findByTriggerMessage 返回已有任务
        when(taskService.create(eq(projectId), eq(actor), any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_task_trigger_message"));
        TaskResponse existing = mock(TaskResponse.class);
        when(taskService.findByTriggerMessage(projectId, messageId, actor)).thenReturn(existing);

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        TaskResponse result = service.trigger(actor, projectId, groupId, messageId, body);

        assertSame(existing, result);
        verify(taskService).findByTriggerMessage(projectId, messageId, actor);
    }

    /** 并发兜底：唯一键冲突但查不到已有任务（异常态）时不得静默吞掉，抛回原异常。 */
    @Test
    void triggerConcurrentDuplicateKeyWithoutExistingRejects() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID(), repoId = UUID.randomUUID();
        MessageEntity message = message(groupId, messageId, "{\"text\":\"@agent 做登录\"}");
        when(messages.selectById(messageId)).thenReturn(message);
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(groupRepos.selectRepositoryIds(groupId)).thenReturn(List.of(repoId));
        when(taskService.create(eq(projectId), eq(actor), any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_task_trigger_message"));
        when(taskService.findByTriggerMessage(projectId, messageId, actor)).thenReturn(null);

        TaskTriggerRequest body = new TaskTriggerRequest();
        body.setTitle("t");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> service.trigger(actor, projectId, groupId, messageId, body));
    }

    private TaskCreateRequest capturedCreateRequest(UUID projectId, UUID actor) {
        ArgumentCaptor<TaskCreateRequest> captor = ArgumentCaptor.forClass(TaskCreateRequest.class);
        verify(taskService).create(eq(projectId), eq(actor), captor.capture());
        return captor.getValue();
    }

    private MessageEntity diffMessage(UUID groupId, UUID id, UUID diffId) {
        return message(groupId, id, "{\"diffId\":\"" + diffId + "\"}", null, "DIFF");
    }

    private MessageEntity message(UUID groupId, UUID id, String content) {
        return message(groupId, id, content, null, null);
    }

    private MessageEntity message(UUID groupId, UUID id, String content, UUID replyToId, String messageType) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setRequirementGroupId(groupId);
        m.setContent(content);
        m.setReplyToMessageId(replyToId);
        m.setMessageType(messageType);
        return m;
    }

    private DiffEntity diff(UUID id, UUID projectId, UUID taskId, UUID workspaceId) {
        DiffEntity d = new DiffEntity();
        d.setId(id);
        d.setProjectId(projectId);
        d.setTaskId(taskId);
        d.setWorkspaceId(workspaceId);
        return d;
    }

    private TaskEntity task(UUID id, UUID projectId, UUID groupId, UUID workspaceId) {
        TaskEntity t = new TaskEntity();
        t.setId(id);
        t.setProjectId(projectId);
        t.setRequirementGroupId(groupId);
        t.setWorkspaceId(workspaceId);
        return t;
    }

    private RequirementGroupEntity group(UUID id, UUID projectId, String type, String status) {
        RequirementGroupEntity g = new RequirementGroupEntity();
        g.setId(id);
        g.setProjectId(projectId);
        g.setGroupType(type);
        g.setStatus(status);
        g.setName("登录功能");
        return g;
    }

    private Mention mention(String type) {
        Mention m = new Mention();
        m.setType(type);
        m.setId(UUID.randomUUID());
        return m;
    }
}
