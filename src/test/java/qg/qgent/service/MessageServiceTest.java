package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
                mock(GroupService.class), mock(TaskTriggerService.class), new ObjectMapper(), mock(EventService.class),
                mock(NotificationService.class));
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
                mock(GroupService.class), mock(TaskTriggerService.class), new ObjectMapper(), mock(EventService.class),
                mock(NotificationService.class));
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
                projects, mock(ProjectAccessService.class), mock(GroupService.class), mock(TaskTriggerService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class));
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
                projects, mock(ProjectAccessService.class), mock(GroupService.class), mock(TaskTriggerService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class));
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
                mock(ProjectAccessService.class), mock(GroupService.class), mock(TaskTriggerService.class),
                new ObjectMapper(), mock(EventService.class), mock(NotificationService.class));

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
}
