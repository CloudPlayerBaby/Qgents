package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import qg.qgent.api.ApiException;
import qg.qgent.dto.MemoryCreateRequest;
import qg.qgent.dto.MemoryResponse;
import qg.qgent.dto.SkillCreateRequest;
import qg.qgent.dto.SkillResponse;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MemoryMessageSourceMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.SkillMapper;
import qg.qgent.mapper.UserMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Project Admin 自建 Skill/Memory 免审批（契约 2026-08-17 决策）：
 * Admin 创建即 PUBLISHED/APPROVED 并发布事件；普通成员创建保持 DRAFT 且无上架事件。
 */
class SkillMemoryAdminAutoPublishTest {

    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final MemoryMapper memoryMapper = mock(MemoryMapper.class);
    private final MemoryMessageSourceMapper sourceMapper = mock(MemoryMessageSourceMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final RequirementGroupMapper groupMapper = mock(RequirementGroupMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final EventService eventService = mock(EventService.class);

    private final SkillService skillService = new SkillService(skillMapper, access, userMapper, eventService);
    private final MemoryService memoryService = new MemoryService(memoryMapper, sourceMapper, messageMapper,
            groupMapper, access, userMapper, mock(ChatClient.Builder.class), new ObjectMapper(), eventService);

    private final UUID projectId = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    @Test
    void skillCreatedByAdminIsPublishedImmediately() {
        when(access.isProjectAdmin(projectId, admin)).thenReturn(true);
        final SkillEntity[] created = new SkillEntity[1];
        when(skillMapper.insert(any(SkillEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return 1;
        });
        // selectById 返回 create 内部对象同一引用：create 对状态的上架修改对 response 可见
        when(skillMapper.selectById(any())).thenAnswer(invocation -> created[0]);

        SkillResponse response = skillService.create(admin, projectId, skillRequest("规范", "PROJECT_SHARED"));

        assertEquals("PUBLISHED", response.getStatus());
        verify(eventService).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    @Test
    void skillCreatedByMemberStaysDraftWhenProjectShared() {
        when(access.isProjectAdmin(projectId, member)).thenReturn(false);
        SkillEntity entity = skillEntity();
        when(skillMapper.selectById(any())).thenReturn(entity);

        SkillResponse response = skillService.create(member, projectId, skillRequest("规范", "PROJECT_SHARED"));

        assertEquals("DRAFT", response.getStatus());
        verify(eventService, never()).publish(any(), any(), eq("skill.published"), any(), any());
    }

    @Test
    void privateSkillCreatedByMemberIsPublishedImmediately() {
        // PRIVATE 仅创建者自己可用，不共享，任何人创建即生效无需审核
        when(access.isProjectAdmin(projectId, member)).thenReturn(false);
        final SkillEntity[] created = new SkillEntity[1];
        when(skillMapper.insert(any(SkillEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return 1;
        });
        when(skillMapper.selectById(any())).thenAnswer(invocation -> created[0]);

        SkillResponse response = skillService.create(member, projectId, skillRequest("个人工具", "PRIVATE"));

        assertEquals("PUBLISHED", response.getStatus());
        assertEquals("PRIVATE", response.getVisibility());
        verify(eventService).publish(eq(projectId), isNull(), eq("skill.published"), any(), any());
    }

    @Test
    void memoryCreatedByAdminIsApprovedImmediately() {
        when(access.isProjectAdmin(projectId, admin)).thenReturn(true);
        when(sourceMapper.selectMessageIds(any())).thenReturn(java.util.List.of());
        final MemoryEntity[] created = new MemoryEntity[1];
        when(memoryMapper.insert(any(MemoryEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return 1;
        });
        when(memoryMapper.selectById(any())).thenAnswer(invocation -> created[0]);

        MemoryResponse response = memoryService.create(admin, projectId, memoryRequest("登录约定"));

        assertEquals("APPROVED", response.getStatus());
        verify(eventService).publish(eq(projectId), isNull(), eq("memory.approved"), any(), any());
    }

    @Test
    void memoryCreatedByMemberStaysDraft() {
        when(access.isProjectAdmin(projectId, member)).thenReturn(false);
        MemoryEntity entity = memoryEntity();
        when(memoryMapper.selectById(any())).thenReturn(entity);
        when(sourceMapper.selectMessageIds(any())).thenReturn(java.util.List.of());

        MemoryResponse response = memoryService.create(member, projectId, memoryRequest("登录约定"));

        assertEquals("DRAFT", response.getStatus());
        verify(eventService, never()).publish(any(), any(), eq("memory.approved"), any(), any());
    }

    private SkillCreateRequest skillRequest(String name, String visibility) {
        SkillCreateRequest request = new SkillCreateRequest();
        request.setName(name);
        request.setContent("内容");
        request.setVisibility(visibility);
        return request;
    }

    private MemoryCreateRequest memoryRequest(String title) {
        MemoryCreateRequest request = new MemoryCreateRequest();
        request.setTitle(title);
        request.setContent("内容");
        request.setCategory("ENGINEERING_DECISION");
        return request;
    }

    private SkillEntity skillEntity() {
        SkillEntity skill = new SkillEntity();
        skill.setId(UUID.randomUUID());
        skill.setProjectId(projectId);
        skill.setCreatedBy(admin);
        skill.setName("规范");
        skill.setContent("内容");
        skill.setStatus("DRAFT");
        skill.setVisibility("PROJECT_SHARED");
        return skill;
    }

    private MemoryEntity memoryEntity() {
        MemoryEntity memory = new MemoryEntity();
        memory.setId(UUID.randomUUID());
        memory.setProjectId(projectId);
        memory.setCreatedBy(admin);
        memory.setTitle("登录约定");
        memory.setContent("内容");
        memory.setStatus("DRAFT");
        return memory;
    }
}
