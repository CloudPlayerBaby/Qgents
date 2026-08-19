package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.GroupCreateRequest;
import qg.qgent.dto.GroupMemberResponse;
import qg.qgent.dto.GroupReadResponse;
import qg.qgent.dto.GroupResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
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
import qg.qgent.mapper.UserMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群成员选择与管理（契约 2026-08-17）：建群初始成员、成员列表改源、邀请/移出与群成员可见性。
 */
class GroupServiceMemberTest {

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
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService eventService = mock(EventService.class);

    private final GroupService service = new GroupService(groupMapper, groupRepoMapper, projectMemberMapper,
            projectMapper, groupAgentMapper, groupMemberMapper, agentMapper, userMapper,
            messageMapper, groupReadStateMapper, access, eventService);

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

    private RequirementGroupEntity mainGroup() {
        RequirementGroupEntity group = requirementGroup();
        group.setGroupType("PROJECT_MAIN");
        return group;
    }

    @BeforeEach
    void stubDefaults() {
        when(groupMapper.selectById(groupId)).thenReturn(requirementGroup());
        // toResponse 依赖：成员数/仓库/Agent 为空
        when(projectMemberMapper.countMembers(projectId)).thenReturn(0L);
        when(groupAgentMapper.selectAgentIds(any())).thenReturn(List.of());
        when(groupRepoMapper.selectRepositoryIds(any())).thenReturn(List.of());
    }

    @Test
    void createWritesCreatorAndMemberIdsWithDedup() {
        // 创建者与 memberIds 均为项目成员
        when(projectMemberMapper.selectByProjectAndUser(eq(projectId), any())).thenReturn(projectMember());
        when(groupMapper.selectById(any())).thenReturn(requirementGroup());

        GroupCreateRequest body = new GroupCreateRequest();
        body.setTitle("登录功能");
        body.setMemberIds(List.of(memberA, memberA, creator));

        service.create(creator, projectId, body);

        // 群 ID 由服务端新生成（UuidV7），按成员 ID 校验写入
        verify(groupMemberMapper).insertMember(any(), eq(creator));
        verify(groupMemberMapper).insertMember(any(), eq(memberA));
    }

    @Test
    void createSkipsCreatorProjectMembershipValidation() {
        // 创建者由服务端自动入群；本测试仅验证创建者不参与显式项目成员校验。
        when(projectMemberMapper.selectByProjectAndUser(projectId, memberA)).thenReturn(projectMember());
        when(groupMapper.selectById(any())).thenReturn(requirementGroup());

        GroupCreateRequest body = new GroupCreateRequest();
        body.setTitle("登录功能");
        body.setMemberIds(List.of(creator, memberA));

        service.create(creator, projectId, body);

        verify(projectMemberMapper, never()).selectByProjectAndUser(projectId, creator);
        verify(groupMemberMapper).insertMember(any(), eq(creator));
        verify(groupMemberMapper).insertMember(any(), eq(memberA));
    }

    @Test
    void createRejectsNonProjectMemberInMemberIds() {
        when(projectMemberMapper.selectByProjectAndUser(projectId, memberA)).thenReturn(null);

        GroupCreateRequest body = new GroupCreateRequest();
        body.setTitle("登录功能");
        body.setMemberIds(List.of(memberA));

        ApiException ex = assertThrows(ApiException.class, () -> service.create(creator, projectId, body));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("GROUP_MEMBER_NOT_PROJECT_MEMBER", ex.code());
    }

    @Test
    void leaveRejectsCanonicalTeamOwnerBecauseAccessWouldRemain() {
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(access.isCanonicalTeamOwner(teamId, creator)).thenReturn(true);

        ApiException error = assertThrows(ApiException.class, () -> service.leave(creator, projectId, groupId));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("TEAM_OWNER_CANNOT_LEAVE_PROJECT", error.code());
        verify(projectMemberMapper, never()).deleteByProjectAndUser(projectId, creator);
    }

    @Test
    void leaveClearsRequirementGroupMembershipsBeforeRemovingProjectMembership() {
        when(projectMemberMapper.selectByProjectAndUser(projectId, creator)).thenReturn(projectMember());

        service.leave(creator, projectId, groupId);

        verify(groupMemberMapper).deleteByProjectAndUser(projectId, creator);
        verify(projectMemberMapper).deleteByProjectAndUser(projectId, creator);
    }

    @Test
    void membersForRequirementGroupReturnsExplicitMembersPlusAgents() {
        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(1);
        when(groupMemberMapper.selectMembersWithUsers(groupId)).thenReturn(List.of(
                memberRow(memberA, "张三", "zhang@example.com")));
        when(groupAgentMapper.selectAgentIds(groupId)).thenReturn(List.of(UUID.randomUUID()));
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setName("编排助手");
        when(agentMapper.selectById(any())).thenReturn(agent);

        List<GroupMemberResponse> members = service.members(memberA, projectId, groupId);

        assertTrue(members.stream().anyMatch(m -> "USER".equals(m.getMemberType())
                && "zhang@example.com".equals(m.getEmail())));
        assertTrue(members.stream().anyMatch(m -> "AGENT".equals(m.getMemberType())));
    }

    @Test
    void addMemberRejectsMainGroupAndNonProjectMember() {
        when(groupMapper.selectById(groupId)).thenReturn(mainGroup());
        ApiException mainGroupEx = assertThrows(ApiException.class,
                () -> service.addMember(creator, projectId, groupId, memberA));
        assertEquals("SYSTEM_GROUP_MANAGED", mainGroupEx.code());

        when(groupMapper.selectById(groupId)).thenReturn(requirementGroup());
        when(projectMemberMapper.selectByProjectAndUser(projectId, memberA)).thenReturn(null);
        ApiException notProjectMember = assertThrows(ApiException.class,
                () -> service.addMember(creator, projectId, groupId, memberA));
        assertEquals("GROUP_MEMBER_NOT_PROJECT_MEMBER", notProjectMember.code());
    }

    @Test
    void addMemberRequiresGroupAdmin() {
        when(projectMemberMapper.selectByProjectAndUser(projectId, memberA)).thenReturn(projectMember());
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "非创建者/非 Project Admin"))
                .when(access).requireProjectAdmin(eq(projectId), any());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.addMember(UUID.randomUUID(), projectId, groupId, memberA));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
    }

    @Test
    void removeMemberBlocksCreatorAndMissingUser() {
        ApiException creatorEx = assertThrows(ApiException.class,
                () -> service.removeMember(creator, projectId, groupId, creator));
        assertEquals("GROUP_CREATOR_NOT_REMOVABLE", creatorEx.code());

        when(groupMemberMapper.deleteMember(groupId, memberA)).thenReturn(0);
        ApiException missing = assertThrows(ApiException.class,
                () -> service.removeMember(creator, projectId, groupId, memberA));
        assertEquals("GROUP_MEMBER_NOT_FOUND", missing.code());

        when(groupMemberMapper.deleteMember(groupId, memberA)).thenReturn(1);
        service.removeMember(creator, projectId, groupId, memberA);
        verify(eventService).publish(eq(projectId), eq(groupId), eq("group.member.updated"), eq(groupId.toString()), any());
    }

    @Test
    void requireGroupMemberAllowsCreatorAndBlocksOutsider() {
        // 创建者兜底为成员
        when(groupMemberMapper.countMember(groupId, creator)).thenReturn(0);
        service.requireGroupMember(projectId, groupId, creator);
        verify(groupMemberMapper, never()).countMember(groupId, creator);

        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(0);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.requireGroupMember(projectId, groupId, memberA));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("GROUP_MEMBER_REQUIRED", ex.code());
    }

    @Test
    void requireGroupMemberAllowsAllProjectMembersForMainGroup() {
        when(groupMapper.selectById(groupId)).thenReturn(mainGroup());
        service.requireGroupMember(projectId, groupId, memberA);
        verify(groupMemberMapper, never()).countMember(any(), any());
    }

    @Test
    void listReturnsOnlyGroupsVisibleToCurrentUser() {
        when(groupMapper.listVisibleByProject(projectId, memberA)).thenReturn(List.of(requirementGroup()));
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of());
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(List.of(groupId), memberA, memberA.toString())).thenReturn(List.of());

        List<GroupResponse> groups = service.list(memberA, projectId);

        assertEquals(List.of(groupId.toString()), groups.stream().map(GroupResponse::getId).toList());
        verify(groupMapper).listVisibleByProject(projectId, memberA);
        verify(groupMapper, never()).listByProject(projectId);
    }

    @Test
    void emptyVisibleGroupListDoesNotQueryMessages() {
        when(groupMapper.listVisibleByProject(projectId, memberA)).thenReturn(List.of());

        assertTrue(service.list(memberA, projectId).isEmpty());

        verify(messageMapper, never()).selectLatestByGroupIds(any());
        verify(messageMapper, never()).countUnreadByGroupIds(any(), any());
        verify(messageMapper, never()).countMentionUnreadByGroupIds(any(), any(), any());
    }

    @Test
    void groupDetailsOnlyCountUnreadForRequestedGroup() {
        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(1);
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(List.of(groupId), memberA, memberA.toString())).thenReturn(List.of());

        GroupResponse response = service.get(memberA, projectId, groupId);

        assertEquals(groupId.toString(), response.getId());
        verify(messageMapper).countUnreadByGroupIds(List.of(groupId), memberA);
        verify(messageMapper).countMentionUnreadByGroupIds(List.of(groupId), memberA, memberA.toString());
    }

    @Test
    void privateGroupReadOperationsRejectNonMember() {
        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(0);

        ApiException get = assertThrows(ApiException.class, () -> service.get(memberA, projectId, groupId));
        ApiException members = assertThrows(ApiException.class, () -> service.members(memberA, projectId, groupId));
        ApiException markRead = assertThrows(ApiException.class,
                () -> service.markRead(memberA, projectId, groupId));

        assertEquals("GROUP_MEMBER_REQUIRED", get.code());
        assertEquals("GROUP_MEMBER_REQUIRED", members.code());
        assertEquals("GROUP_MEMBER_REQUIRED", markRead.code());
    }

    @Test
    void markReadAdvancesCursorWithoutServiceIdempotency() {
        when(groupMemberMapper.countMember(groupId, memberA)).thenReturn(1);
        when(messageMapper.nextSequence(groupId)).thenReturn(8L);

        GroupReadResponse response = service.markRead(memberA, projectId, groupId);

        assertEquals(groupId.toString(), response.getGroupId());
        assertEquals(7L, response.getLastReadSequenceNo());
        verify(groupReadStateMapper).upsertSequence(memberA, groupId, 7L);
    }

    private static ProjectMemberEntity projectMember() {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectId(UUID.randomUUID());
        member.setUserId(UUID.randomUUID());
        member.setRole("PROJECT_MEMBER");
        return member;
    }

    private static qg.qgent.dto.GroupMemberRow memberRow(UUID userId, String name, String email) {
        qg.qgent.dto.GroupMemberRow row = new qg.qgent.dto.GroupMemberRow();
        row.setUserId(userId);
        row.setDisplayName(name);
        row.setEmail(email);
        return row;
    }
}
