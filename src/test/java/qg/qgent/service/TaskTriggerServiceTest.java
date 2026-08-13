package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.Mention;
import qg.qgent.dto.TaskResponse;
import qg.qgent.dto.TaskTriggerRequest;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 点7：从群消息触发 Task 的转换服务测试（组装、@agent 自动触发、缺仓库跳过、幂等）。 */
class TaskTriggerServiceTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final RequirementGroupRepositoryMapper groupRepos = mock(RequirementGroupRepositoryMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskTriggerService service = new TaskTriggerService(messages, groups, groupRepos, tasks, taskService,
            access, mapper);

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

    private MessageEntity message(UUID groupId, UUID id, String content) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setRequirementGroupId(groupId);
        m.setContent(content);
        return m;
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
