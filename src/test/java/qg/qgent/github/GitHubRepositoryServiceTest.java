package qg.qgent.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import qg.qgent.api.ApiException;
import qg.qgent.dto.BindProjectRepositoryRequest;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.service.GitHubRepositoryService;

@ExtendWith(MockitoExtension.class)
class GitHubRepositoryServiceTest {
    private final UUID actorId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();

    @Mock private GitHubInstallationMapper installationMapper;
    @Mock private GitHubRepositoryMapper repositoryMapper;
    @Mock private ProjectRepositoryMapper projectRepositoryMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private RepositoryBranchConfigMapper branchConfigMapper;
    @Mock private GitHubAppClient gitHubClient;

    private GitHubRepositoryService service;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "GitHubRepositoryServiceTest");
        TableInfoHelper.initTableInfo(assistant, GitHubInstallationEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectEntity.class);
        TableInfoHelper.initTableInfo(assistant, RepositoryBranchConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new GitHubRepositoryService(installationMapper, repositoryMapper, projectRepositoryMapper,
                projectMapper, projectMemberMapper, teamMemberMapper, branchConfigMapper, gitHubClient,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void bindsAuthorizedRepositoryUsingItsDefaultBranch() {
        GitHubRepositoryEntity repository = repository("main");
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(repository);
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ProjectRepositoryResponse response = service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, null, "Backend"));

        ArgumentCaptor<ProjectRepositoryEntity> binding = ArgumentCaptor.forClass(ProjectRepositoryEntity.class);
        verify(projectRepositoryMapper).insert(binding.capture());
        assertEquals(repositoryId, response.getRepositoryId());
        assertEquals("main", response.getDefaultBranch());
        assertEquals("Backend", binding.getValue().getDisplayName());
    }

    @Test
    void rejectsDuplicateProjectBinding() {
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(repository("main"));
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(new ProjectRepositoryEntity());

        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, null, null)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_ALREADY_BOUND", exception.code());
        verify(projectRepositoryMapper, never()).insert(any(ProjectRepositoryEntity.class));
    }

    @Test
    void rejectsRepositoryOutsideProjectTeamInstallation() {
        authorizeProjectAdmin();

        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, "main", null)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT", exception.code());
    }

    @Test
    void rejectsRepositoryWhenRequestInstallationIsNotAuthorizedForProjectTeam() {
        GitHubRepositoryEntity repository = repository("main");
        authorizeProjectAdmin();

        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(UUID.randomUUID(), repository.getId(), "main", null)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT", exception.code());
        verify(repositoryMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void rejectsNonAdminBeforeLookingUpRepository() {
        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, "main", null)));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verify(repositoryMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void refusesToUnbindRepositoryReferencedByBranchConfiguration() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectById(projectRepositoryId)).thenReturn(binding);
        when(branchConfigMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.unbindProjectRepository(actorId, projectId, projectRepositoryId));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG", exception.code());
        verify(projectRepositoryMapper, never()).deleteById(projectRepositoryId);
    }

    private GitHubRepositoryEntity repository(String branch) {
        GitHubRepositoryEntity repository = new GitHubRepositoryEntity();
        repository.setId(repositoryId);
        repository.setInstallationId(installationId);
        repository.setProviderRepositoryId(42L);
        repository.setOwnerLogin("qgents");
        repository.setName("backend");
        repository.setDefaultBranch(branch);
        repository.setVisibility("PRIVATE");
        repository.setArchived(false);
        repository.setSyncedAt(Instant.now());
        return repository;
    }

    private void authorizeProjectAdmin() {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(teamMemberMapper.countByTeamIdAndUserIdAndRole(any(UUID.class), any(UUID.class), anyString())).thenReturn(0L);
        when(projectMemberMapper.countByProjectIdAndUserIdAndRole(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(1L);
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        org.mockito.Mockito.lenient().when(installationMapper.selectList(any(Wrapper.class)))
                .thenReturn(java.util.List.of(installation));
    }

    private BindProjectRepositoryRequest bindRequest(UUID requestedInstallationId, UUID requestedRepositoryId,
            String defaultBranch, String displayName) {
        BindProjectRepositoryRequest request = new BindProjectRepositoryRequest();
        request.setInstallationId(requestedInstallationId);
        request.setRepositoryId(requestedRepositoryId);
        request.setDefaultBranch(defaultBranch);
        request.setDisplayName(displayName);
        return request;
    }
}
