package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.api.ApiException;
import qg.qgent.dto.CreateProjectRequest;
import qg.qgent.dto.AddProjectMemberRequest;
import qg.qgent.dto.ProjectMembershipView;
import qg.qgent.dto.UpdateProjectMemberRequest;
import qg.qgent.dto.UpdateProjectRequest;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.dto.NewProjectRepositoryRequest;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubRepositoryDetails;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMemberMapper members = mock(ProjectMemberMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final EventService eventService = mock(EventService.class);
    private final GitHubRepositoryService githubRepositoryService = mock(GitHubRepositoryService.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final ProjectService service = new ProjectService(projects, members, teams, teamMembers, access,
            eventPublisher, notificationService, eventService, githubRepositoryService, projectRepositories);

    @Test
    void createAddsCreatorAdminAndUniqueInitialMembers() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        TeamMemberEntity ownerMember = teamMember(teamId, owner);
        ownerMember.setRole("TEAM_OWNER");
        when(teamMembers.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);
        when(teamMembers.selectByTeamAndUser(teamId, member)).thenReturn(teamMember(teamId, member));
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName(" project ");
        request.setDescription("description");
        request.setMemberIds(List.of(owner, member, member));

        var response = service.create(owner, teamId, request);

        assertEquals("project", response.getName());
        assertEquals("PROJECT_ADMIN", response.getRole());
        verify(projects).insert(any(ProjectEntity.class));
        verify(members, org.mockito.Mockito.times(2)).insert(any(ProjectMemberEntity.class));
    }

    @Test
    void createAutoProvisionsNewRepository() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(teams.selectById(teamId)).thenReturn(team(teamId, owner));
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        TeamMemberEntity ownerMember = teamMember(teamId, owner);
        ownerMember.setRole("TEAM_OWNER");
        when(teamMembers.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);

        NewProjectRepositoryRequest newRepo = new NewProjectRepositoryRequest();
        newRepo.setName("auto-repo");
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("project");
        request.setNewRepository(newRepo);

        GitHubRepositoryService.RemoteRepositoryCreation creation = new GitHubRepositoryService.RemoteRepositoryCreation(
                new GitHubInstallationEntity(), new GitHubRepositoryDetails());
        when(githubRepositoryService.createRemoteRepository(owner, teamId, newRepo)).thenReturn(creation);

        service.create(owner, teamId, request);

        verify(githubRepositoryService).createRemoteRepository(owner, teamId, newRepo);
        verify(githubRepositoryService).bindCreatedRepository(any(UUID.class), eq(creation), eq(newRepo));
        verify(githubRepositoryService, never()).bindRepositoriesOnCreate(any(UUID.class), any(UUID.class),
                any(UUID.class), any());
    }

    @Test
    void createCompensatesRemoteRepositoryWhenLocalBindingRollsBack() {
        UUID teamId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(teams.selectById(teamId)).thenReturn(team(teamId, owner));
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        TeamMemberEntity ownerMember = teamMember(teamId, owner);
        ownerMember.setRole("TEAM_OWNER");
        when(teamMembers.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);

        NewProjectRepositoryRequest newRepo = new NewProjectRepositoryRequest();
        newRepo.setName("auto-repo");
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("project");
        request.setNewRepository(newRepo);
        GitHubRepositoryService.RemoteRepositoryCreation creation = new GitHubRepositoryService.RemoteRepositoryCreation(
                new GitHubInstallationEntity(), new GitHubRepositoryDetails());
        when(githubRepositoryService.createRemoteRepository(owner, teamId, newRepo)).thenReturn(creation);
        doThrow(new IllegalStateException("local binding failed")).when(githubRepositoryService)
                .bindCreatedRepository(any(UUID.class), eq(creation), eq(newRepo));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> service.create(owner, teamId, request));
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(githubRepositoryService).deleteRemoteRepository(creation);
    }

    @Test
    void cannotDowngradeLastProjectAdmin() {
        UUID projectId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId, "ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, UUID.randomUUID()));
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
        when(members.selectByProjectAndUser(projectId, admin)).thenReturn(projectMember(projectId, admin,
                "PROJECT_ADMIN"));
        when(members.countAdmins(projectId)).thenReturn(1);
        UpdateProjectMemberRequest request = new UpdateProjectMemberRequest();
        request.setRole("PROJECT_MEMBER");

        ApiException error = assertThrows(ApiException.class,
                () -> service.updateMember(admin, projectId, admin, request));

        assertEquals("LAST_PROJECT_ADMIN_REQUIRED", error.code());
    }

    @Test
    void cannotRemoveLastProjectAdmin() {
        UUID projectId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId, "ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, UUID.randomUUID()));
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
        when(members.selectByProjectAndUser(projectId, admin))
                .thenReturn(projectMember(projectId, admin, "PROJECT_ADMIN"));
        when(members.countAdmins(projectId)).thenReturn(1);

        ApiException error = assertThrows(ApiException.class,
                () -> service.removeMember(admin, projectId, admin));

        assertEquals("LAST_PROJECT_ADMIN_REQUIRED", error.code());
    }

    @Test
    void addMemberRejectsUserOutsideTeam() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId, "ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, UUID.randomUUID()));
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(outsider);

        ApiException error = assertThrows(ApiException.class,
                () -> service.addMember(actor, projectId, request));

        assertEquals("TEAM_MEMBER_REQUIRED", error.code());
    }

    @Test
    void addingMemberLocksTeamBeforeProject() {
        UUID projectId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId, "ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team(teamId, UUID.randomUUID()));
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
        when(teamMembers.selectByTeamAndUser(teamId, userId)).thenReturn(teamMember(teamId, userId));
        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(userId);

        service.addMember(actor, projectId, request);

        var order = org.mockito.Mockito.inOrder(projects, teams, members);
        order.verify(projects).selectById(projectId);
        order.verify(teams).selectByIdForUpdate(teamId);
        order.verify(projects).selectByIdForUpdate(projectId);
        order.verify(members).insert(any(ProjectMemberEntity.class));
    }

    @Test
    void archiveAndRestoreAreRepeatableStateTransitions() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ProjectEntity project = project(projectId, UUID.randomUUID(), "ACTIVE");
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);

        assertEquals("ARCHIVED", service.archive(actor, projectId).getStatus());
        assertEquals("ARCHIVED", service.archive(actor, projectId).getStatus());
        assertEquals("ACTIVE", service.restore(actor, projectId).getStatus());
    }

    @Test
    void archivedWriteAuthorizesBeforeReturningStateError() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ProjectEntity project = project(projectId, UUID.randomUUID(), "ARCHIVED");
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
        doThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                "项目不存在或不可见")).when(access).requireProjectAdminAnyState(project, actor);
        UpdateProjectRequest request = new UpdateProjectRequest();
        request.setName("renamed");

        ApiException error = assertThrows(ApiException.class,
                () -> service.update(actor, projectId, request));

        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    @Test
    void listUsesDefaultLimitAndReturnsScopedCursor() {
        UUID teamId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(teams.selectById(teamId)).thenReturn(team(teamId, UUID.randomUUID()));
        when(teamMembers.selectByTeamAndUser(teamId, actor)).thenReturn(teamMember(teamId, actor));
        List<ProjectMembershipView> rows = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            ProjectMembershipView row = new ProjectMembershipView();
            row.setId(new UUID(0, i + 1));
            row.setTeamId(teamId);
            row.setName("p" + i);
            row.setRole("PROJECT_MEMBER");
            row.setStatus("ACTIVE");
            rows.add(row);
        }
        when(projects.selectAccessiblePage(teamId, actor, false, null, 31)).thenReturn(rows);

        var page = service.list(actor, teamId, null, null);

        assertEquals(30, page.getData().size());
        assertEquals(true, page.getPage().isHasMore());
    }

    /** 项目详情 response 补齐 memberCount 与 repositoryCount（前端避免逐卡 N+1）。 */
    @Test
    void getFillsMemberAndRepositoryCounts() {
        UUID teamId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ProjectEntity project = project(projectId, teamId, "ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(access.requireAccess(project, actor)).thenReturn("PROJECT_MEMBER");
        when(members.countMembers(projectId)).thenReturn(9L);
        when(projectRepositories.countActiveByProject(projectId)).thenReturn(4L);

        var response = service.get(actor, projectId);

        assertEquals(9L, response.getMemberCount());
        assertEquals(4L, response.getRepositoryCount());
    }

    private TeamEntity team(UUID id, UUID owner) {
        TeamEntity value = new TeamEntity();
        value.setId(id);
        value.setOwnerUserId(owner);
        value.setStatus("ACTIVE");
        return value;
    }

    private TeamMemberEntity teamMember(UUID teamId, UUID userId) {
        TeamMemberEntity value = new TeamMemberEntity();
        value.setTeamId(teamId);
        value.setUserId(userId);
        value.setRole("TEAM_MEMBER");
        return value;
    }

    private ProjectEntity project(UUID id, UUID teamId, String status) {
        ProjectEntity value = new ProjectEntity();
        value.setId(id);
        value.setTeamId(teamId);
        value.setName("project");
        value.setStatus(status);
        return value;
    }

    private ProjectMemberEntity projectMember(UUID projectId, UUID userId, String role) {
        ProjectMemberEntity value = new ProjectMemberEntity();
        value.setProjectId(projectId);
        value.setUserId(userId);
        value.setRole(role);
        return value;
    }
}
