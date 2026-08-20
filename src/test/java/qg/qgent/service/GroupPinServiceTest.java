package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.GroupPinResponse;
import qg.qgent.dto.GroupResponse;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.GroupMemberMapper;
import qg.qgent.mapper.GroupReadStateMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;
import qg.qgent.mapper.UserGroupPreferenceMapper;
import qg.qgent.mapper.UserMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群聊置顶（个人偏好持久化）：列表返回 pinned、setPinned 幂等 upsert、非项目成员 403。
 */
class GroupPinServiceTest {

    private final RequirementGroupMapper groupMapper = mock(RequirementGroupMapper.class);
    private final RequirementGroupRepositoryMapper groupRepoMapper = mock(RequirementGroupRepositoryMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final GroupAgentMapper groupAgentMapper = mock(GroupAgentMapper.class);
    private final GroupMemberMapper groupMemberMapper = mock(GroupMemberMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final GroupReadStateMapper groupReadStateMapper = mock(GroupReadStateMapper.class);
    private final UserGroupPreferenceMapper userGroupPreferenceMapper = mock(UserGroupPreferenceMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService eventService = mock(EventService.class);

    private final GroupService service = new GroupService(groupMapper, groupRepoMapper, projectMemberMapper,
            projectMapper, groupAgentMapper, groupMemberMapper, agentMapper, userMapper,
            messageMapper, groupReadStateMapper, userGroupPreferenceMapper, access, eventService);

    private final UUID projectId = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    private RequirementGroupEntity requirementGroup() {
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setCreatedBy(creator);
        group.setGroupType("REQUIREMENT");
        group.setStatus("ACTIVE");
        return group;
    }

    @BeforeEach
    void stubDefaults() {
        when(groupMapper.selectById(groupId)).thenReturn(requirementGroup());
        when(groupMemberMapper.countMember(groupId, creator)).thenReturn(1);
        when(projectMemberMapper.countMembers(projectId)).thenReturn(0L);
        when(groupAgentMapper.selectAgentIds(any())).thenReturn(List.of());
        when(groupRepoMapper.selectRepositoryIds(any())).thenReturn(List.of());
    }

    @Test
    void listMarksPinnedGroups() {
        when(groupMapper.listVisibleByProject(projectId, memberA)).thenReturn(List.of(requirementGroup()));
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of());
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(List.of(groupId), memberA, memberA.toString())).thenReturn(List.of());
        when(userGroupPreferenceMapper.selectPinnedGroupIds(memberA, List.of(groupId))).thenReturn(List.of(groupId));

        List<GroupResponse> groups = service.list(memberA, projectId);

        assertEquals(1, groups.size());
        assertTrue(groups.get(0).getPinned());
        verify(userGroupPreferenceMapper).selectPinnedGroupIds(memberA, List.of(groupId));
    }

    @Test
    void listDefaultsUnpinnedGroupsToFalse() {
        when(groupMapper.listVisibleByProject(projectId, memberA)).thenReturn(List.of(requirementGroup()));
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of());
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(List.of(groupId), memberA, memberA.toString())).thenReturn(List.of());
        // 默认 stub：selectPinnedGroupIds 返回空 → 未置顶
        when(userGroupPreferenceMapper.selectPinnedGroupIds(memberA, List.of(groupId))).thenReturn(List.of());

        List<GroupResponse> groups = service.list(memberA, projectId);

        assertEquals(1, groups.size());
        assertFalse(groups.get(0).getPinned());
    }

    @Test
    void setPinnedUpsertsAndReturnsStatus() {
        GroupPinResponse response = service.setPinned(creator, projectId, groupId, true);

        assertEquals(groupId.toString(), response.getGroupId());
        assertTrue(response.getPinned());
        verify(userGroupPreferenceMapper).upsertPin(creator, groupId, true);
    }

    @Test
    void setPinnedUnpinUpsertsFalse() {
        GroupPinResponse response = service.setPinned(creator, projectId, groupId, false);

        assertEquals(groupId.toString(), response.getGroupId());
        assertFalse(response.getPinned());
        verify(userGroupPreferenceMapper).upsertPin(creator, groupId, false);
    }

    @Test
    void setPinnedRequiresProjectMember() {
        // 非项目成员：requireGroupMember 抛 403 GROUP_MEMBER_REQUIRED（默认 mock 未 stub countMember）
        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.setPinned(memberA, projectId, groupId, true));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        verify(userGroupPreferenceMapper, never()).upsertPin(any(), any(), anyBoolean());
    }

    @Test
    void setPinnedRejectsGroupNotInProject() {
        // 群不属于该项目：requireGroupInProject 抛 404
        when(groupMapper.selectById(groupId)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.setPinned(creator, projectId, groupId, true));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        verify(userGroupPreferenceMapper, never()).upsertPin(any(), any(), anyBoolean());
    }
}
