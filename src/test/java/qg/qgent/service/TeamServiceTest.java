package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.api.PersistedApiException;
import qg.qgent.auth.TeamInvitationMailer;
import qg.qgent.auth.TokenService;
import qg.qgent.dto.CreateTeamRequest;
import qg.qgent.dto.TeamResponse;
import qg.qgent.dto.UpdateTeamMemberRequest;
import qg.qgent.dto.InviteTeamMemberRequest;
import qg.qgent.dto.TeamMembershipView;
import qg.qgent.dto.TeamMemberView;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.GroupMemberMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TeamInvitationMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.UserMapper;

import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.argThat;

class TeamServiceTest {

    /**
     * 纯单元测试未启动 MyBatis/Spring，lambda 包装器依赖实体 TableInfo；显式注册
     * 涉及到的实体，避免裸 JVM 下懒初始化列缓存的行为差异导致测试偶发失败。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TeamEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamInvitationEntity.class);
    }

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamInvitationMapper invitationMapper = mock(TeamInvitationMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final GroupMemberMapper groupMemberMapper = mock(GroupMemberMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TeamDisbandService teamDisbandService = mock(TeamDisbandService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final DefaultAgentProvisioner defaultAgents = mock(DefaultAgentProvisioner.class);
    private final TeamService service = new TeamService(teamMapper, memberMapper, invitationMapper,
            projectMemberMapper, projectMapper, userMapper, mock(TokenService.class),
            mock(TeamInvitationMailer.class), teamDisbandService, notificationService,
            mock(EventService.class), eventMapper, taskMapper, mergeRequestMapper, defaultAgents, groupMemberMapper);

    @Test
    void createAddsCreatorAsOwner() {
        UUID actor = UUID.randomUUID();
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName(" 研发团队 ");

        // create() 插入后重查团队以带回数据库生成的 created_at，这里返回刚插入的实体
        java.util.concurrent.atomic.AtomicReference<TeamEntity> inserted =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        }).when(teamMapper).insert(any(TeamEntity.class));
        when(teamMapper.selectById(any(UUID.class))).thenAnswer(inv -> inserted.get());

        TeamResponse response = service.create(actor, request);

        assertEquals("研发团队", response.getName());
        assertEquals("TEAM_OWNER", response.getRole());
        assertEquals(0, response.getMemberCount());
        verify(teamMapper).insert(any(TeamEntity.class));
        verify(memberMapper).insert(any(TeamMemberEntity.class));
        // 建团队即预置团队默认 Agent（4 工作角色 + 编排助手）
        org.mockito.Mockito.verify(defaultAgents).ensureForTeam(org.mockito.ArgumentMatchers.argThat(
                teamId -> teamId != null), org.mockito.ArgumentMatchers.eq(actor));
    }

    @Test
    void ordinaryMemberCannotUpdateTeamRole() {
        UUID teamId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TeamMemberEntity actorMember = member(teamId, actor, "TEAM_MEMBER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, actor));
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(actorMember);
        UpdateTeamMemberRequest request = new UpdateTeamMemberRequest();
        request.setRole("TEAM_OWNER");

        ApiException error = assertThrows(ApiException.class,
                () -> service.updateMember(actor, teamId, UUID.randomUUID(), request));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
    }

    @Test
    void primaryOwnerCannotBeRemoved() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        TeamMemberEntity ownerMember = member(teamId, owner, "TEAM_OWNER");
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team);
        ApiException error = assertThrows(ApiException.class, () -> service.removeMember(owner, teamId, owner));

        assertEquals("TEAM_OWNER_IMMUTABLE", error.code());
    }

    @Test
    void primaryOwnerCannotBeDowngraded() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        UpdateTeamMemberRequest request = new UpdateTeamMemberRequest();
        request.setRole("TEAM_MEMBER");

        ApiException error = assertThrows(ApiException.class,
                () -> service.updateMember(owner, teamId, owner, request));

        assertEquals("TEAM_OWNER_IMMUTABLE", error.code());
    }

    @Test
    void extraOwnerRoleDoesNotGrantOwnerPermission() {
        UUID teamId = UUID.randomUUID();
        UUID primary = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        TeamEntity team = team(teamId, primary);
        TeamMemberEntity primaryMember = member(teamId, primary, "TEAM_OWNER");
        TeamMemberEntity otherMember = member(teamId, otherOwner, "TEAM_OWNER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team);
        when(memberMapper.selectByTeamAndUser(teamId, otherOwner)).thenReturn(otherMember);
        when(memberMapper.selectByTeamAndUser(teamId, primary)).thenReturn(primaryMember);

        ApiException error = assertThrows(ApiException.class,
                () -> service.removeMember(otherOwner, teamId, primary));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
    }

    @Test
    void memberRoleEndpointCannotCreateAnotherOwner() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        UpdateTeamMemberRequest request = new UpdateTeamMemberRequest();
        request.setRole("TEAM_OWNER");

        ApiException error = assertThrows(ApiException.class,
                () -> service.updateMember(owner, teamId, memberId, request));

        assertEquals("INVALID_TEAM_OPERATION", error.code());
    }

    @Test
    void removingTeamMemberCannotOrphanProjectAdminRole() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, admin)).thenReturn(member(teamId, admin, "TEAM_MEMBER"));
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectByTeamForUpdate(teamId)).thenReturn(java.util.List.of(project));
        qg.qgent.entity.ProjectMemberEntity projectMember = new qg.qgent.entity.ProjectMemberEntity();
        projectMember.setProjectId(projectId);
        projectMember.setUserId(admin);
        projectMember.setRole("PROJECT_ADMIN");
        when(projectMemberMapper.selectByProjectAndUser(projectId, admin)).thenReturn(projectMember);
        when(projectMemberMapper.countAdmins(projectId)).thenReturn(1);

        ApiException error = assertThrows(ApiException.class,
                () -> service.removeMember(owner, teamId, admin));

        assertEquals("LAST_PROJECT_ADMIN_REQUIRED", error.code());
        org.mockito.Mockito.verify(projectMemberMapper, org.mockito.Mockito.never())
                .deleteByTeamAndUser(teamId, admin);
        var order = inOrder(teamMapper, projectMapper, projectMemberMapper);
        order.verify(teamMapper).selectByIdForUpdate(teamId);
        order.verify(projectMapper).selectByTeamForUpdate(teamId);
        order.verify(projectMemberMapper).selectByProjectAndUser(projectId, admin);
        order.verify(projectMemberMapper).countAdmins(projectId);
    }

    @Test
    void removingTeamMemberClearsRequirementGroupMembershipsInEachProject() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID targetUser = UUID.randomUUID();
        UUID firstProjectId = UUID.randomUUID();
        UUID secondProjectId = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, targetUser))
                .thenReturn(member(teamId, targetUser, "TEAM_MEMBER"));
        ProjectEntity first = new ProjectEntity();
        first.setId(firstProjectId);
        first.setTeamId(teamId);
        ProjectEntity second = new ProjectEntity();
        second.setId(secondProjectId);
        second.setTeamId(teamId);
        when(projectMapper.selectByTeamForUpdate(teamId)).thenReturn(java.util.List.of(first, second));

        service.removeMember(owner, teamId, targetUser);

        verify(groupMemberMapper).deleteByProjectAndUser(firstProjectId, targetUser);
        verify(groupMemberMapper).deleteByProjectAndUser(secondProjectId, targetUser);
        verify(projectMemberMapper).deleteByTeamAndUser(teamId, targetUser);
    }

    @Test
    void canonicalOwnerCanNormalizeExtraOwnerRole() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID extraOwner = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, extraOwner))
                .thenReturn(member(teamId, extraOwner, "TEAM_OWNER"));
        UpdateTeamMemberRequest request = new UpdateTeamMemberRequest();
        request.setRole("TEAM_MEMBER");

        var response = service.updateMember(owner, teamId, extraOwner, request);

        assertEquals("TEAM_MEMBER", response.getRole());
        verify(memberMapper).updateRole(teamId, extraOwner, "TEAM_MEMBER");
    }

    @Test
    void teamDetailsNormalizeExtraOwnerRole() {
        UUID teamId = UUID.randomUUID();
        UUID canonicalOwner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(member(teamId, actor, "TEAM_OWNER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));

        var response = service.get(actor, teamId);

        assertEquals("TEAM_MEMBER", response.getRole());
    }

    @Test
    void memberListNormalizesExtraOwnerRole() {
        UUID teamId = UUID.randomUUID();
        UUID canonicalOwner = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID extraOwner = UUID.randomUUID();
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(member(teamId, actor, "TEAM_MEMBER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));
        TeamMemberView memberView = new TeamMemberView();
        memberView.setTeamId(teamId);
        memberView.setUserId(extraOwner);
        memberView.setRole("TEAM_OWNER");
        when(memberMapper.selectMemberPage(teamId, null, 31)).thenReturn(java.util.List.of(memberView));

        var response = service.members(actor, teamId, null, null);

        assertEquals("TEAM_MEMBER", response.getData().get(0).getRole());
    }

    @Test
    void acceptExpiredInvitationPersistsExpiredBeforeReturningError() {
        UUID actor = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(actor);
        user.setEmail("member@example.com");
        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setId(invitationId);
        invitation.setEmailNormalized("member@example.com");
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        TokenService tokens = mock(TokenService.class);
        TeamService localService = new TeamService(teamMapper, memberMapper, invitationMapper, projectMemberMapper,
                projectMapper, userMapper, tokens, mock(TeamInvitationMailer.class), mock(TeamDisbandService.class),
                mock(NotificationService.class), mock(EventService.class), eventMapper, taskMapper, mergeRequestMapper,
                defaultAgents, groupMemberMapper);
        when(userMapper.selectById(actor)).thenReturn(user);
        when(tokens.hash("raw-token")).thenReturn(new byte[] { 1 });
        when(invitationMapper.selectOne(any())).thenReturn(invitation);

        PersistedApiException error = assertThrows(PersistedApiException.class,
                () -> localService.accept(actor, "raw-token"));

        assertEquals("INVITATION_EXPIRED", error.code());
        verify(invitationMapper).updateById(org.mockito.ArgumentMatchers.<TeamInvitationEntity>argThat(
                value -> "EXPIRED".equals(value.getStatus())));
    }

    @Test
    void revokeExpiredInvitationPersistsExpiredInsteadOfRevoked() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));
        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setId(invitationId);
        invitation.setTeamId(teamId);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(invitationMapper.selectOne(any())).thenReturn(invitation);

        PersistedApiException error = assertThrows(PersistedApiException.class,
                () -> service.revoke(owner, teamId, invitationId));

        assertEquals("INVITATION_EXPIRED", error.code());
        verify(invitationMapper).updateById(org.mockito.ArgumentMatchers.<TeamInvitationEntity>argThat(
                value -> "EXPIRED".equals(value.getStatus())));
    }

    @Test
    void inviteLocksTeamBeforeCheckingForPendingInvitation() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        TeamMemberEntity ownerMember = member(teamId, owner, "TEAM_OWNER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);
        TokenService tokens = mock(TokenService.class);
        when(tokens.opaque()).thenReturn("raw-token");
        when(tokens.hash("raw-token")).thenReturn(new byte[] { 1 });
        TeamService localService = new TeamService(teamMapper, memberMapper, invitationMapper, projectMemberMapper,
                projectMapper, userMapper, tokens, mock(TeamInvitationMailer.class), mock(TeamDisbandService.class),
                mock(NotificationService.class), mock(EventService.class), eventMapper, taskMapper, mergeRequestMapper,
                defaultAgents, groupMemberMapper);
        InviteTeamMemberRequest request = new InviteTeamMemberRequest();
        request.setEmail("new@example.com");
        request.setRole("TEAM_MEMBER");
        request.setExpiresInDays(7);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            localService.invite(owner, teamId, request);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        var order = inOrder(teamMapper, memberMapper, invitationMapper);
        order.verify(teamMapper).selectByIdForUpdate(teamId);
        order.verify(memberMapper).selectByTeamAndUser(teamId, owner);
        order.verify(invitationMapper).selectOne(any());
    }

    @Test
    void listUsesDefaultThirtyAndReturnsCursor() {
        UUID actor = UUID.randomUUID();
        java.util.List<TeamMembershipView> firstRows = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            UUID teamId = new UUID(0, i + 1L);
            TeamMembershipView row = new TeamMembershipView();
            row.setId(teamId);
            row.setOwnerUserId(UUID.randomUUID());
            row.setName("team-" + i);
            row.setRole(i == 0 ? "TEAM_OWNER" : "TEAM_MEMBER");
            firstRows.add(row);
        }
        when(teamMapper.selectMembershipPage(actor, null, 31)).thenReturn(firstRows);
        when(teamMapper.selectMembershipPage(actor, new UUID(0, 30L), 31))
                .thenReturn(java.util.List.of(firstRows.get(30)));

        var page = service.list(actor, null, null);

        assertEquals(30, page.getData().size());
        assertEquals("TEAM_MEMBER", page.getData().get(0).getRole());
        assertEquals(true, page.getPage().isHasMore());

        var nextPage = service.list(actor, page.getPage().getNextCursor(), null);
        assertEquals(1, nextPage.getData().size());
        assertEquals(false, nextPage.getPage().isHasMore());
    }

    @Test
    void cursorCannotBeReusedAcrossListScopes() {
        UUID actor = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        TeamMembershipView first = new TeamMembershipView();
        first.setId(new UUID(0, 1));
        first.setOwnerUserId(UUID.randomUUID());
        first.setName("one");
        first.setRole("TEAM_MEMBER");
        TeamMembershipView second = new TeamMembershipView();
        second.setId(new UUID(0, 2));
        second.setOwnerUserId(UUID.randomUUID());
        second.setName("two");
        second.setRole("TEAM_MEMBER");
        when(teamMapper.selectMembershipPage(actor, null, 2)).thenReturn(java.util.List.of(first, second));
        String teamListCursor = service.list(actor, null, 1).getPage().getNextCursor();
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(member(teamId, actor, "TEAM_MEMBER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, UUID.randomUUID()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.members(actor, teamId, teamListCursor, 1));

        assertEquals("INVALID_CURSOR", error.code());
    }

    @Test
    void onlyOwnerCanDisbandTeam() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, memberId)).thenReturn(member(teamId, memberId, "TEAM_MEMBER"));

        ApiException error = assertThrows(ApiException.class, () -> service.disband(memberId, teamId));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
        verify(teamDisbandService, org.mockito.Mockito.never()).deleteTeam(any());
    }

    @Test
    void ownerDisbandDelegatesToDisbandService() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(member(teamId, owner, "TEAM_OWNER"));

        TeamResponse response = service.disband(owner, teamId);

        assertEquals("TEAM_OWNER", response.getRole());
        assertEquals(teamId.toString(), response.getId());
        verify(teamDisbandService).deleteTeam(teamId);
    }

    @Test
    void disbandUnknownTeamReturnsNotFound() {
        UUID actor = UUID.randomUUID();
        when(teamMapper.selectByIdForUpdate(any())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.disband(actor, UUID.randomUUID()));

        assertEquals("TEAM_RESOURCE_NOT_FOUND", error.code());
        verify(teamDisbandService, org.mockito.Mockito.never()).deleteTeam(any());
    }

    private TeamMemberEntity member(UUID teamId, UUID userId, String role) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private TeamEntity team(UUID teamId, UUID ownerId) {
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(ownerId);
        team.setStatus("ACTIVE");
        return team;
    }
}
