package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService access = new ProjectAccessService(projects, projectMembers, teams,
            teamMembers);

    @Test
    void canonicalTeamOwnerIsAdminWithoutProjectMembership() {
        UUID projectId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectById(teamId)).thenReturn(team(teamId, owner));
        when(teamMembers.selectByTeamAndUser(teamId, owner)).thenReturn(teamMember(teamId, owner, "TEAM_OWNER"));

        access.requireProjectAdmin(projectId, owner);

        assertEquals("PROJECT_ADMIN", access.requireProjectMember(projectId, owner));
    }

    @Test
    void extraOwnerRoleDoesNotGrantProjectFallback() {
        UUID projectId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID canonicalOwner = UUID.randomUUID();
        UUID extraOwner = UUID.randomUUID();
        when(projects.selectById(projectId)).thenReturn(project(projectId, teamId));
        when(teams.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));
        when(teamMembers.selectByTeamAndUser(teamId, extraOwner))
                .thenReturn(teamMember(teamId, extraOwner, "TEAM_OWNER"));

        ApiException error = assertThrows(ApiException.class,
                () -> access.requireProjectAdmin(projectId, extraOwner));

        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    private ProjectEntity project(UUID id, UUID teamId) {
        ProjectEntity value = new ProjectEntity();
        value.setId(id);
        value.setTeamId(teamId);
        value.setStatus("ACTIVE");
        return value;
    }

    private TeamEntity team(UUID id, UUID owner) {
        TeamEntity value = new TeamEntity();
        value.setId(id);
        value.setOwnerUserId(owner);
        return value;
    }

    private TeamMemberEntity teamMember(UUID teamId, UUID userId, String role) {
        TeamMemberEntity value = new TeamMemberEntity();
        value.setTeamId(teamId);
        value.setUserId(userId);
        value.setRole(role);
        return value;
    }
}
