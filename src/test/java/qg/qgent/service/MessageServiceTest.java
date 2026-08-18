package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MessageResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.UserMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 自动化兜底卡必须保留业务消息类型，确保无编排 Agent 时 Diff 仍可被渲染和引用续作。
 */
class MessageServiceTest {

    private static void initializeMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(RequirementGroupEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "MessageServiceTest"),
                    RequirementGroupEntity.class);
        }
    }

    @Test
    void systemDiffCardKeepsDiffTypeAndHasNoSender() {
        initializeMybatisMetadata();
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
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectAccessService.class),
                mock(TaskTriggerService.class), new ObjectMapper(), mock(EventService.class));
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
                mock(UserMapper.class), mock(AgentMapper.class), mock(ProjectAccessService.class),
                mock(TaskTriggerService.class), new ObjectMapper(), mock(EventService.class));
        MessageSendRequest request = new MessageSendRequest();
        request.setType("TEXT");
        request.setContent(Map.of("text", "cannot impersonate a system card"));

        assertThatThrownBy(() -> service.sendAsSystem(group.getId(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_MESSAGE_TYPE_INVALID"));
        verifyNoInteractions(messages);
    }
}
