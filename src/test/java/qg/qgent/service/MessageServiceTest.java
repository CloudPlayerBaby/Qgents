package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MessageResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.UserMapper;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 自动化兜底卡必须保留业务消息类型，确保无编排 Agent 时 Diff 仍可被渲染和引用续作。
 */
class MessageServiceTest {

    @BeforeEach
    void initializeMetadataBeforeEachTest() {
        initializeMybatisMetadata();
    }

    private static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(RequirementGroupEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    RequirementGroupEntity.class);
        }
        if (TableInfoHelper.getTableInfo(MessageEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    MessageEntity.class);
        }
    }

    @Test
    void systemDiffCardKeepsDiffTypeAndHasNoSender() {
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        UUID groupId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());

        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class), mock(ProjectAccessService.class),
                mock(GroupService.class), new ObjectMapper(), mock(EventService.class),
                mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        UUID diffId = UUID.randomUUID();
        request.setType("DIFF");
        request.setContent(Map.of("diffId", diffId.toString()));

        MessageResponse response = service.sendAsSystem(groupId, request);

        ArgumentCaptor<MessageEntity> message = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insert(message.capture());
        assertThat(message.getValue().getMessageType()).isEqualTo("DIFF");
        assertThat(message.getValue().getAuthorUserId()).isNull();
        assertThat(message.getValue().getAgentId()).isNull();
        assertThat(response.getType()).isEqualTo("DIFF");
        assertThat(response.getSenderType()).isEqualTo("SYSTEM");
        assertThat(response.getContent()).containsEntry("diffId", diffId.toString());
    }

    @Test
    void systemMessageRejectsNonCardType() {
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(UUID.randomUUID());
        group.setProjectId(UUID.randomUUID());
        when(groups.selectOne(any())).thenReturn(group);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class), mock(ProjectAccessService.class),
                mock(GroupService.class), new ObjectMapper(), mock(EventService.class),
                mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "cannot impersonate a system card"));

        assertThatThrownBy(() -> service.sendAsSystem(group.getId(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_MESSAGE_TYPE_INVALID"));
        verifyNoInteractions(messages);
    }

    @Test
    void agentMessageRejectsMissingCrossTeamAndInactiveAgentBeforeWriting() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(projects.selectById(projectId)).thenReturn(project);
        MessageService service = new MessageService(messages, groups, groupAgents, mock(UserMapper.class), agents,
                projects, mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "任务更新"));

        ApiException missing = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(missing.code()).isEqualTo("AGENT_NOT_FOUND");

        AgentEntity crossTeam = new AgentEntity();
        crossTeam.setTeamId(UUID.randomUUID());
        crossTeam.setStatus("ACTIVE");
        when(agents.selectById(agentId)).thenReturn(crossTeam);
        ApiException wrongTeam = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(wrongTeam.code()).isEqualTo("AGENT_NOT_IN_PROJECT_TEAM");

        AgentEntity inactive = new AgentEntity();
        inactive.setTeamId(teamId);
        inactive.setStatus("INACTIVE");
        when(agents.selectById(agentId)).thenReturn(inactive);
        ApiException disabled = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.sendAsAgent(groupId, agentId, request));
        assertThat(disabled.code()).isEqualTo("AGENT_NOT_ACTIVE");
        verifyNoInteractions(messages, groupAgents);
    }

    @Test
    void activeAgentInProjectTeamCanSendAndJoinGroup() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        when(groupAgents.insertAgent(groupId, agentId)).thenReturn(1);
        MessageService service = new MessageService(messages, groups, groupAgents, mock(UserMapper.class), agents,
                projects, mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "任务更新"));

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        assertThat(response.getSenderType()).isEqualTo("AGENT");
        verify(messages).insert(any(MessageEntity.class));
        verify(groupAgents).insertAgent(groupId, agentId);
    }

    @Test
    void taskStatusCardUpsertKeepsMessageIdentityAndPlanSnapshot() {
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        UUID groupId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(7L);
        AtomicReference<MessageEntity> stored = new AtomicReference<>();
        when(messages.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> stored.get());

        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        MessageSendRequest initial = new MessageSendRequest();
        initial.setType("TASK_STATUS");
        initial.setContent(new java.util.LinkedHashMap<>(Map.of(
                "taskId", taskId.toString(), "status", "PLANNING",
                "plan", Map.of("summary", "分析权限", "steps", java.util.List.of()))));
        MessageResponse created = service.upsertTaskStatusCard(groupId, null, initial);
        UUID messageId = stored.get().getId();
        LocalDateTime createdAt = stored.get().getCreatedAt();

        MessageSendRequest update = new MessageSendRequest();
        update.setType("TASK_STATUS");
        update.setContent(new java.util.LinkedHashMap<>(Map.of(
                "taskId", taskId.toString(), "status", "RUNNING", "phase", "CODING")));
        MessageResponse changed = service.upsertTaskStatusCard(groupId, null, update);

        assertThat(created.getId()).isEqualTo(messageId.toString());
        assertThat(changed.getId()).isEqualTo(messageId.toString());
        assertThat(stored.get().getSequenceNo()).isEqualTo(7L);
        assertThat(stored.get().getCreatedAt()).isEqualTo(createdAt);
        assertThat(changed.getContent()).containsEntry("status", "RUNNING");
        assertThat(((Map<?, ?>) changed.getContent().get("plan")).get("summary"))
                .isEqualTo("分析权限");
        verify(messages).updateById(any(MessageEntity.class));
    }

    @Test
    void userMessageWithSameClientMessageIdIsWrittenOnlyOnce() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> stored = new AtomicReference<>();
        when(messages.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> stored.get());
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class), new ObjectMapper(),
                mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "@编排助手 补齐邮箱登录功能"));
        request.setMentions(List.of());
        request.setClientMessageId("manual-task-trigger-" + UUID.randomUUID());

        MessageResponse first = service.send(actor, projectId, groupId, request);
        MessageResponse second = service.send(actor, projectId, groupId, request);

        assertThat(second.getId()).isEqualTo(first.getId());
        verify(messages, times(1)).insert(any(MessageEntity.class));
    }

    @Test
    void quoteMessageEchoesTopLevelReplyText() {
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        ProjectEntity project = new ProjectEntity();
        project.setId(group.getProjectId());
        project.setTeamId(teamId);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectById(agentId)).thenReturn(agent);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(group.getProjectId())).thenReturn(project);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), agents, projects,
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("QUOTE");
        request.setContent(new java.util.LinkedHashMap<>(Map.of(
                "quotedMessageId", UUID.randomUUID().toString(),
                "quotedText", "密码存储怎么没加密",
                "quotedSenderName", "张同学")));
        request.setReplyText("这里回复正文");
        request.setReplyToId(UUID.randomUUID());
        MessageEntity quoted = new MessageEntity();
        quoted.setRequirementGroupId(groupId);
        when(messages.selectById(request.getReplyToId())).thenReturn(quoted);

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        ArgumentCaptor<MessageEntity> captured = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insert(captured.capture());
        String storedContent = captured.getValue().getContent();
        assertThat(storedContent).contains("replyText");
        assertThat(response.getType()).isEqualTo("QUOTE");
        assertThat(response.getReplyText()).isEqualTo("这里回复正文");
        assertThat(response.getContent()).containsEntry("replyText", "这里回复正文");
    }

    @Test
    void quoteMessageWithoutReplyTextKeepsEchoEmpty() {
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        UUID groupId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        group.setId(groupId);
        group.setProjectId(UUID.randomUUID());
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setName("编排助手");
        agent.setStatus("ACTIVE");
        ProjectEntity project = new ProjectEntity();
        project.setId(group.getProjectId());
        project.setTeamId(teamId);
        when(groups.selectOne(any())).thenReturn(group);
        when(messages.nextSequence(groupId)).thenReturn(1L);
        AtomicReference<MessageEntity> inserted = new AtomicReference<>();
        when(messages.insert(any(MessageEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(messages.selectById(any())).thenAnswer(invocation -> inserted.get());
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectById(agentId)).thenReturn(agent);
        ProjectMapper projects = mock(ProjectMapper.class);
        when(projects.selectById(group.getProjectId())).thenReturn(project);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), agents, projects,
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        MessageSendRequest request = new MessageSendRequest();
        request.setType("QUOTE");
        request.setContent(new java.util.LinkedHashMap<>(Map.of(
                "quotedMessageId", UUID.randomUUID().toString(),
                "quotedText", "纯引用不带回复",
                "quotedSenderName", "李同学")));

        MessageResponse response = service.sendAsAgent(groupId, agentId, request);

        assertThat(response.getReplyText()).isNull();
        assertThat(response.getContent()).doesNotContainKey("replyText");
    }

    @Test
    void incrementalMessagesAreReadAfterCursorInAscendingOrder() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        GroupService groupService = mock(GroupService.class);
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageEntity first = message(groupId, 11L, "第一条");
        MessageEntity second = message(groupId, 12L, "第二条");
        when(messages.selectAfterSequence(groupId, 10L, 3)).thenReturn(List.of(first, second));

        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), groupService,
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        var page = service.listAfterSequence(actor, projectId, groupId, 10L, 2);

        assertThat(page.getData()).extracting(MessageResponse::getSequence).containsExactly(11L, 12L);
        assertThat(page.getPage().isHasMore()).isFalse();
        verify(groupService).requireGroupMember(projectId, groupId, actor);
        verify(messages).selectAfterSequence(groupId, 10L, 3);
    }

    @Test
    void incrementalMessagesRejectNegativeCursorBeforeQuery() {
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        MessageMapper messages = mock(MessageMapper.class);
        RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
        when(groups.selectById(groupId)).thenReturn(group);
        MessageService service = new MessageService(messages, groups, mock(GroupAgentMapper.class),
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectMapper.class),
                mock(ProjectAccessService.class), mock(GroupService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class), mock(AttachmentService.class),
                mock(ApplicationEventPublisher.class));

        assertThatThrownBy(() -> service.listAfterSequence(UUID.randomUUID(), projectId, groupId, -1, 100))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_MESSAGE_CURSOR"));
        verify(messages, never()).selectAfterSequence(any(), anyLong(), anyInt());
    }

    private MessageEntity message(UUID groupId, long sequence, String text) {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setRequirementGroupId(groupId);
        entity.setSequenceNo(sequence);
        entity.setMessageType("TEXT");
        entity.setContent("{\"text\":\"" + text + "\"}");
        entity.setMentions("[]");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
