package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MemoryDraftRequest;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MemoryMessageSourceMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.UserMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Memory 自动沉淀（AI 草稿）业务测试。
 */
class MemoryServiceTest {

    private final MemoryMapper memories = mock(MemoryMapper.class);
    private final MemoryMessageSourceMapper sources = mock(MemoryMessageSourceMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final UserMapper users = mock(UserMapper.class);

    private MemoryService service(String aiJson) {
        return new MemoryService(memories, sources, messages, groups, access, users,
                builder(aiJson), new com.fasterxml.jackson.databind.ObjectMapper(), mock(EventService.class));
    }

    private ChatClient.Builder builder(String aiJson) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = org.mockito.Mockito.mock(ChatClient.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        if (aiJson != null) {
            when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(aiJson);
        }
        return builder;
    }

    @Test
    void createAiDraftAutoRetrievesRecentGroupMessagesAsMessageSource() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();

        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);

        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        MessageEntity first = message(m1, groupId, 1L, "{\"text\":\"我们决定用 B 方案\"}");
        MessageEntity second = message(m2, groupId, 2L, "{\"text\":\"crypto 用 AES，密钥走 KMS 管理\"}");
        // service 请求按 sequence 倒序取最近 N 条；mock 直接返回两条
        when(messages.selectList(any())).thenReturn(List.of(first, second));

        MemoryEntity persisted = new MemoryEntity();
        persisted.setId(memoryId);
        persisted.setProjectId(projectId);
        persisted.setTitle("认证安全约定");
        persisted.setContent("密码密钥经 KMS 管理");
        persisted.setCategory("ENGINEERING_DECISION");
        persisted.setTags(List.of("security"));
        persisted.setStatus("DRAFT");
        when(memories.selectById(any())).thenReturn(persisted);
        when(sources.selectMessageIds(any())).thenReturn(List.of(m1, m2));

        MemoryDraftRequest request = new MemoryDraftRequest();
        request.setGroupId(groupId);

        var response = service("{\"title\":\"认证安全约定\",\"content\":\"密码密钥经 KMS 管理\","
                + "\"category\":\"ENGINEERING_DECISION\",\"tags\":[\"security\"]}")
                .createAiDraft(actor, projectId, request);

        verify(sources).insertSource(any(), eq(m1));
        verify(sources).insertSource(any(), eq(m2));
        assertEquals("MESSAGE", response.getSource());
        assertEquals(2, response.getSources().size());
        assertEquals("认证安全约定", response.getTitle());
    }

    @Test
    void createAiDraftRejectsGroupNotInProject() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        RequirementGroupEntity foreign = new RequirementGroupEntity();
        foreign.setId(groupId);
        foreign.setProjectId(UUID.randomUUID());
        when(groups.selectById(groupId)).thenReturn(foreign);

        MemoryDraftRequest request = new MemoryDraftRequest();
        request.setGroupId(groupId);

        ApiException error = assertThrows(ApiException.class,
                () -> service(null).createAiDraft(actor, projectId, request));

        assertEquals("GROUP_NOT_IN_PROJECT", error.code());
        verify(memories, never()).insert(any(MemoryEntity.class));
    }

    @Test
    void createAiDraftRejectsEmptyGroupWithoutCallingLlm() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();

        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        when(messages.selectList(any())).thenReturn(List.of());

        MemoryDraftRequest request = new MemoryDraftRequest();
        request.setGroupId(groupId);

        // 空群直接拒绝（422 GROUP_NO_MESSAGES），不调用 LLM，避免空上下文消耗 token
        ApiException ex = assertThrows(ApiException.class,
                () -> service(null).createAiDraft(actor, projectId, request));
        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("GROUP_NO_MESSAGES", ex.code());
    }

    private MessageEntity message(UUID id, UUID groupId, Long seq, String content) {
        MessageEntity value = new MessageEntity();
        value.setId(id);
        value.setRequirementGroupId(groupId);
        value.setSequenceNo(seq);
        value.setMessageType("TEXT");
        value.setContent(content);
        return value;
    }
}
