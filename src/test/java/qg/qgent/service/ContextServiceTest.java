package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ContextSearchResponse;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.SkillMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群上下文必须遵循需求群成员可见性，不能绕过消息接口的访问校验。
 */
class ContextServiceTest {

    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final MemoryMapper memories = mock(MemoryMapper.class);
    private final RequirementGroupRepositoryMapper groupRepositories = mock(RequirementGroupRepositoryMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final ContextService service = new ContextService(groups, messages, skills, memories, groupRepositories,
            access, groupService, new ObjectMapper());

    private final UUID actor = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void buildForGroupRejectsNonMemberBeforeReadingContent() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED", "你不是该需求群成员，无法访问"))
                .when(groupService).requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.buildForGroup(actor, projectId, groupId, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("GROUP_MEMBER_REQUIRED"));
        verify(groups, never()).selectById(any());
        verify(messages, never()).selectList(any());
    }

    @Test
    void searchRejectsNonMemberSpecifiedGroup() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED", "你不是该需求群成员，无法访问"))
                .when(groupService).requireGroupMember(projectId, groupId, actor);

        assertThatThrownBy(() -> service.search(actor, projectId, "登录", null, groupId, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("GROUP_MEMBER_REQUIRED"));
        verify(messages, never()).searchByQuery(any(), any(), any(), anyInt());
    }

    @Test
    void projectSearchRestrictsMessagesToVisibleGroups() {
        UUID mainGroupId = UUID.randomUUID();
        UUID joinedGroupId = UUID.randomUUID();
        List<UUID> visible = List.of(mainGroupId, joinedGroupId);
        when(groupService.visibleGroupIds(projectId, actor)).thenReturn(visible);
        when(messages.searchByQuery(projectId, visible, "登录", 20)).thenReturn(List.of());

        ContextSearchResponse response = service.search(actor, projectId, "登录", null, null, 20);

        assertThat(response.getMessages()).isEmpty();
        verify(messages).searchByQuery(projectId, visible, "登录", 20);
    }

    @Test
    void projectSearchSkipsMessageQueryWhenNoGroupsAreVisible() {
        when(groupService.visibleGroupIds(projectId, actor)).thenReturn(List.of());

        ContextSearchResponse response = service.search(actor, projectId, "登录", null, null, 20);

        assertThat(response.getMessages()).isEmpty();
        verify(messages, never()).searchByQuery(any(), any(), any(), anyInt());
    }
}
