package qg.qgent.service;

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
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.ProjectMemberMapper;
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
    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamInvitationMapper invitationMapper = mock(TeamInvitationMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TeamService service = new TeamService(teamMapper, memberMapper, invitationMapper,
            projectMemberMapper, userMapper, mock(TokenService.class), mock(TeamInvitationMailer.class));

    @Test
    void createAddsCreatorAsOwner() {
        UUID actor = UUID.randomUUID();
        CreateTeamRequest request = new CreateTeamRequest();
        request.setName(" 研发团队 ");

        TeamResponse response = service.create(actor, request);

        assertEquals("研发团队", response.getName());
        assertEquals("TEAM_OWNER", response.getRole());
        verify(teamMapper).insert(any(TeamEntity.class));
        verify(memberMapper).insert(any(TeamMemberEntity.class));
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
                userMapper, tokens, mock(TeamInvitationMailer.class));
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
                userMapper, tokens, mock(TeamInvitationMailer.class));
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
            row.setName("team-" + i);
            row.setRole("TEAM_MEMBER");
            firstRows.add(row);
        }
        when(teamMapper.selectMembershipPage(actor, null, 31)).thenReturn(firstRows);
        when(teamMapper.selectMembershipPage(actor, new UUID(0, 30L), 31))
                .thenReturn(java.util.List.of(firstRows.get(30)));

        var page = service.list(actor, null, null);

        assertEquals(30, page.getData().size());
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
        first.setName("one");
        first.setRole("TEAM_MEMBER");
        TeamMembershipView second = new TeamMembershipView();
        second.setId(new UUID(0, 2));
        second.setName("two");
        second.setRole("TEAM_MEMBER");
        when(teamMapper.selectMembershipPage(actor, null, 2)).thenReturn(java.util.List.of(first, second));
        String teamListCursor = service.list(actor, null, 1).getPage().getNextCursor();
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(member(teamId, actor, "TEAM_MEMBER"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.members(actor, teamId, teamListCursor, 1));

        assertEquals("INVALID_CURSOR", error.code());
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
