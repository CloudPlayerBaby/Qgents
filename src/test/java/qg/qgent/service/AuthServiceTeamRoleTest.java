package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import qg.qgent.auth.PasswordResetMailer;
import qg.qgent.auth.RateLimiter;
import qg.qgent.auth.RsaPasswordDecryptor;
import qg.qgent.auth.TokenService;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.dto.ProjectMembershipView;
import qg.qgent.mapper.PasswordResetTokenMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RefreshTokenMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.mapper.UserMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTeamRoleTest {
    @Test
    void meDoesNotExposeExtraOwnerRoleAsCanonicalOwner() {
        UUID userId = UUID.randomUUID();
        UUID canonicalOwner = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UserMapper userMapper = mock(UserMapper.class);
        TeamMapper teamMapper = mock(TeamMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("qgents-dummy-password-not-used")).thenReturn("dummy");
        AuthService service = new AuthService(userMapper, mock(RefreshTokenMapper.class),
                mock(PasswordResetTokenMapper.class), teamMapper, memberMapper, mock(ProjectMapper.class),
                mock(RsaPasswordDecryptor.class), passwords, mock(TokenService.class),
                mock(PasswordResetMailer.class), mock(RateLimiter.class));
        UserEntity user = new UserEntity();
        user.setId(userId);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRole("TEAM_OWNER");
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(canonicalOwner);
        team.setName("team");
        team.setStatus("ACTIVE");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(memberMapper.selectByUserId(userId)).thenReturn(List.of(membership));
        when(teamMapper.selectBatchIds(anyCollection())).thenReturn(List.of(team));

        var response = service.me(userId);

        assertEquals("TEAM_MEMBER", response.getTeams().get(0).getRole());
    }

    @Test
    void meIncludesCanonicalOwnerProjectsWithoutProjectMembership() {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UserMapper userMapper = mock(UserMapper.class);
        TeamMapper teamMapper = mock(TeamMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("qgents-dummy-password-not-used")).thenReturn("dummy");
        AuthService service = new AuthService(userMapper, mock(RefreshTokenMapper.class),
                mock(PasswordResetTokenMapper.class), teamMapper, memberMapper, projectMapper,
                mock(RsaPasswordDecryptor.class), passwords, mock(TokenService.class),
                mock(PasswordResetMailer.class), mock(RateLimiter.class));
        UserEntity user = new UserEntity();
        user.setId(userId);
        ProjectMembershipView project = new ProjectMembershipView();
        project.setId(projectId);
        project.setTeamId(teamId);
        project.setName("owner-project");
        project.setRole("PROJECT_ADMIN");
        project.setStatus("ACTIVE");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(memberMapper.selectByUserId(userId)).thenReturn(List.of());
        when(projectMapper.selectAccessibleByUser(userId)).thenReturn(List.of(project));

        var response = service.me(userId);

        assertEquals(1, response.getProjects().size());
        assertEquals("PROJECT_ADMIN", response.getProjects().get(0).getRole());
    }
}
