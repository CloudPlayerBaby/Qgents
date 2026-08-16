package qg.qgent.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant; import java.time.LocalDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import qg.qgent.api.ApiException;
import qg.qgent.dto.BindProjectRepositoryRequest;
import qg.qgent.dto.NewProjectRepositoryRequest;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubClient;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.DiffMapper;
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
    @Mock private DiffMapper diffMapper;
    @Mock private GitHubAppClient gitHubClient;

    private GitHubRepositoryService service;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, qg.qgent.handler.UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "GitHubRepositoryServiceTest");
        TableInfoHelper.initTableInfo(assistant, GitHubInstallationEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectEntity.class);
        TableInfoHelper.initTableInfo(assistant, RepositoryBranchConfigEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffEntity.class);
    }

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        // lenient：仅 sync 相关测试会触发事务，其余测试该 stub 不被使用
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new GitHubRepositoryService(installationMapper, repositoryMapper, projectRepositoryMapper,
                projectMapper, projectMemberMapper, teamMemberMapper, branchConfigMapper, diffMapper, gitHubClient,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC), transactionManager);
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
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);
        when(branchConfigMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.unbindProjectRepository(actorId, projectId, projectRepositoryId));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG", exception.code());
        verify(projectRepositoryMapper, never()).deleteById(projectRepositoryId);
    }

    @Test
    void refusesToUnbindRepositoryReferencedByDiff() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);
        when(branchConfigMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(diffMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.unbindProjectRepository(actorId, projectId, projectRepositoryId));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_REFERENCED_BY_DIFF", exception.code());
        verify(projectRepositoryMapper, never()).deleteById(projectRepositoryId);
    }

    @Test
    void rejectsRepositoryWithMissingDefaultBranch() {
        GitHubRepositoryEntity repository = repository(null);
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(repository);

        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, "main", null)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_REPOSITORY_METADATA_INCOMPLETE", exception.code());
    }

    @Test
    void rejectsRevokedRepository() {
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class, () -> service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, null, null)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT", exception.code());
    }

    @Test
    void rejectsSyncWhenInstallationBelongsToAnotherTeam() {
        long providerInstallationId = 12345L;
        UUID myTeamId = UUID.randomUUID();
        UUID otherTeamId = UUID.randomUUID();
        
        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(myTeamId);
        when(gitHubClient.getInstallation(providerInstallationId)).thenReturn(new qg.qgent.github.GitHubInstallationDetails(providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of());
        
        GitHubInstallationEntity existingInstallation = new GitHubInstallationEntity();
        existingInstallation.setId(UUID.randomUUID());
        existingInstallation.setTeamId(otherTeamId);
        existingInstallation.setProviderInstallationId(providerInstallationId);
        
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(existingInstallation);
        
        ApiException exception = assertThrows(ApiException.class, 
                () -> service.handleInstallationCallback(providerInstallationId, "mock_state"));
                
        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_INSTALLATION_TEAM_CONFLICT", exception.code());
    }

    @Test
    void syncInstallationRevokesMissingRepositories() {
        long providerInstallationId = 12345L;
        UUID myTeamId = UUID.randomUUID();
        
        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(myTeamId);
        when(gitHubClient.getInstallation(providerInstallationId)).thenReturn(new qg.qgent.github.GitHubInstallationDetails(providerInstallationId, "qgents", "Organization"));
        
        // Return 1 repository from GitHub
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
            new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)
        ));
        
        GitHubInstallationEntity existingInstallation = new GitHubInstallationEntity();
        existingInstallation.setId(installationId);
        existingInstallation.setTeamId(myTeamId);
        existingInstallation.setProviderInstallationId(providerInstallationId);
        existingInstallation.setStatus("ACTIVE"); // 只有 ACTIVE 安装才继续落库同步
        
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(existingInstallation);
        
        // Return 2 repositories from Database
        GitHubRepositoryEntity repo1 = new GitHubRepositoryEntity();
        repo1.setId(UUID.randomUUID());
        repo1.setInstallationId(installationId);
        repo1.setProviderRepositoryId(100L);
        repo1.setAuthorizationStatus("AUTHORIZED");

        GitHubRepositoryEntity repo2 = new GitHubRepositoryEntity();
        repo2.setId(UUID.randomUUID());
        repo2.setInstallationId(installationId);
        repo2.setProviderRepositoryId(200L); // Missing from GitHub
        repo2.setAuthorizationStatus("AUTHORIZED");
        
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(repo1, repo2));
        
        service.handleInstallationCallback(providerInstallationId, "mock_state");
        
        // Verify repo2 is revoked
        ArgumentCaptor<GitHubRepositoryEntity> captor = ArgumentCaptor.forClass(GitHubRepositoryEntity.class);
        verify(repositoryMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        
        GitHubRepositoryEntity revokedRepo = captor.getAllValues().stream()
                .filter(r -> "REVOKED".equals(r.getAuthorizationStatus()))
                .findFirst().orElseThrow();
        assertEquals(repo2.getId(), revokedRepo.getId());
    }

    @Test
    void handleInstallationCallbackPerformsSyncAndReturnsTeamId() {
        long providerInstallationId = 12345L;
        UUID myTeamId = UUID.randomUUID();
        
        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(myTeamId);
        when(gitHubClient.getInstallation(providerInstallationId)).thenReturn(new qg.qgent.github.GitHubInstallationDetails(providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
            new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)
        ));
        
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(null);
        
        UUID returnedTeamId = service.handleInstallationCallback(providerInstallationId, "mock_state");
        
        assertEquals(myTeamId, returnedTeamId);
        verify(installationMapper).insert(any(GitHubInstallationEntity.class));
        verify(repositoryMapper).insert(any(GitHubRepositoryEntity.class));
    }

    @Test
    void movesExistingRepositoryToNewInstallationForSameTeam() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();
        UUID previousInstallationId = UUID.randomUUID();
        GitHubRepositoryEntity existingRepository = repository("main");
        existingRepository.setInstallationId(previousInstallationId);
        existingRepository.setProviderRepositoryId(100L);

        GitHubInstallationEntity previousInstallation = new GitHubInstallationEntity();
        previousInstallation.setId(previousInstallationId);
        previousInstallation.setTeamId(teamId);

        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(teamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(null);
        when(installationMapper.selectById(previousInstallationId)).thenReturn(previousInstallation);
        when(repositoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(java.util.List.of(), java.util.List.of(existingRepository));

        service.handleInstallationCallback(providerInstallationId, "mock_state");

        ArgumentCaptor<GitHubInstallationEntity> installation = ArgumentCaptor.forClass(GitHubInstallationEntity.class);
        ArgumentCaptor<GitHubRepositoryEntity> repository = ArgumentCaptor.forClass(GitHubRepositoryEntity.class);
        verify(installationMapper).insert(installation.capture());
        verify(repositoryMapper).updateById(repository.capture());
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
        assertEquals(existingRepository.getId(), repository.getValue().getId());
        assertEquals(installation.getValue().getId(), repository.getValue().getInstallationId());
    }

    @Test
    void rejectsExistingRepositoryFromAnotherTeam() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();
        UUID previousInstallationId = UUID.randomUUID();
        GitHubRepositoryEntity existingRepository = repository("main");
        existingRepository.setInstallationId(previousInstallationId);
        existingRepository.setProviderRepositoryId(100L);

        GitHubInstallationEntity previousInstallation = new GitHubInstallationEntity();
        previousInstallation.setId(previousInstallationId);
        previousInstallation.setTeamId(UUID.randomUUID());

        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(teamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(null);
        when(installationMapper.selectById(previousInstallationId)).thenReturn(previousInstallation);
        when(repositoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(java.util.List.of(), java.util.List.of(existingRepository));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.handleInstallationCallback(providerInstallationId, "mock_state"));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_REPOSITORY_INSTALLATION_CONFLICT", exception.code());
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
        verify(repositoryMapper, never()).updateById(any(GitHubRepositoryEntity.class));
    }

    @Test
    void syncsEmptyRepositoryWithoutDefaultBranch() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();
        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(teamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "empty-repository", null,
                        "PRIVATE", false)));
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(null);

        service.handleInstallationCallback(providerInstallationId, "mock_state");

        ArgumentCaptor<GitHubRepositoryEntity> repository = ArgumentCaptor.forClass(GitHubRepositoryEntity.class);
        verify(repositoryMapper).insert(repository.capture());
        assertNull(repository.getValue().getDefaultBranch());
    }

    @Test
    void syncDoesNotRestoreSuspendedInstallationFromStaleSnapshot() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();

        // 锁外 GitHub 快照正常返回（旧快照，安装当时仍可用）
        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(teamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));

        // 落库阶段行锁读到：安装已被 Webhook suspend 置为 SUSPENDED
        GitHubInstallationEntity suspended = new GitHubInstallationEntity();
        suspended.setId(installationId);
        suspended.setTeamId(teamId);
        suspended.setProviderInstallationId(providerInstallationId);
        suspended.setStatus("SUSPENDED");
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(suspended);

        service.handleInstallationCallback(providerInstallationId, "mock_state");

        // 不得用旧快照恢复 ACTIVE，不得写回 AUTHORIZED 仓库
        verify(installationMapper, never()).updateById(any(GitHubInstallationEntity.class));
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
        verify(repositoryMapper, never()).updateById(any(GitHubRepositoryEntity.class));
    }

    @Test
    void syncDoesNotRestoreDeletedInstallationFromStaleSnapshot() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();

        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(teamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));

        GitHubInstallationEntity deleted = new GitHubInstallationEntity();
        deleted.setId(installationId);
        deleted.setTeamId(teamId);
        deleted.setProviderInstallationId(providerInstallationId);
        deleted.setStatus("DELETED");
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(deleted);

        service.handleInstallationCallback(providerInstallationId, "mock_state");

        verify(installationMapper, never()).updateById(any(GitHubInstallationEntity.class));
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
        verify(repositoryMapper, never()).updateById(any(GitHubRepositoryEntity.class));
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
        repository.setAuthorizationStatus("AUTHORIZED");
        repository.setSyncedAt(LocalDateTime.now());
        return repository;
    }

    private void authorizeProjectAdmin() {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(projectMemberMapper.selectCount(any(Wrapper.class)))
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

    @Test
    void createInstallationUrlReturnsUrl() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(gitHubClient.createInstallationUrl(teamId, actorId)).thenReturn("https://github.com/install");

        var response = service.createInstallationUrl(actorId, teamId);

        assertEquals("https://github.com/install", response.getInstallationUrl());
        assertNotNull(response.getExpiresAt());
        assertEquals(java.time.ZoneOffset.UTC, response.getExpiresAt().getOffset());
    }

    @Test
    void listInstallationsReturnsMappedInstallations() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity entity = new GitHubInstallationEntity();
        entity.setId(UUID.randomUUID());
        entity.setProviderInstallationId(12345L);
        entity.setAccountLogin("test-login");
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(entity));

        var response = service.listInstallations(actorId, teamId);

        assertEquals(1, response.size());
        assertEquals("test-login", response.get(0).getAccountLogin());
    }

    @Test
    void listTeamRepositoriesReturnsMappedRepositories() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity instEntity = new GitHubInstallationEntity();
        instEntity.setId(UUID.randomUUID());
        instEntity.setProviderInstallationId(12345L);
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(instEntity));

        GitHubRepositoryEntity entity = new GitHubRepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setProviderRepositoryId(12345L);
        entity.setOwnerLogin("owner");
        entity.setName("repo");
        entity.setAuthorizationStatus("AUTHORIZED");
        entity.setArchived(false);
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(entity));

        var response = service.listTeamRepositories(actorId, teamId);

        assertEquals(1, response.size());
        assertEquals("owner/repo", response.get(0).getFullName());
    }

    @Test
    void listProjectRepositoriesReturnsMappedRepositories() {
        UUID projectId = UUID.randomUUID();
        
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        UUID repoId = UUID.randomUUID();

        ProjectRepositoryEntity entity = new ProjectRepositoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(repoId);
        when(projectRepositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(entity));

        GitHubRepositoryEntity repoEntity = new GitHubRepositoryEntity();
        repoEntity.setId(repoId);
        repoEntity.setProviderRepositoryId(12345L);
        repoEntity.setOwnerLogin("owner");
        repoEntity.setName("repo");
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(repoEntity));

        var response = service.listProjectRepositories(actorId, projectId);

        assertEquals(1, response.size());
        assertEquals("owner/repo", response.get(0).getFullName());
    }

    @Test
    void removeInstallationDeletesSuccessfully() {
        UUID teamId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity entity = new GitHubInstallationEntity();
        entity.setId(installationId);
        entity.setTeamId(teamId);
        when(installationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.Collections.emptyList());

        service.removeInstallation(actorId, teamId, installationId);

        verify(installationMapper).deleteById(installationId);
        // 无仓库镜像时不调用镜像删除
        verify(repositoryMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void removeInstallationDeletesUnboundRepositoryMirrorsBeforeInstallation() {
        UUID teamId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity entity = new GitHubInstallationEntity();
        entity.setId(installationId);
        entity.setTeamId(teamId);
        when(installationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);

        // 存在仓库镜像，但无项目绑定
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(installationId);
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(mirror));
        when(projectRepositoryMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.removeInstallation(actorId, teamId, installationId);

        // 先删镜像，再删安装
        verify(repositoryMapper).delete(any(Wrapper.class));
        verify(installationMapper).deleteById(installationId);
    }

    @Test
    void removeInstallationRejectedWhenRepositoryBoundToProject() {
        UUID teamId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity entity = new GitHubInstallationEntity();
        entity.setId(installationId);
        entity.setTeamId(teamId);
        when(installationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);

        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(installationId);
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(mirror));
        when(projectRepositoryMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.removeInstallation(actorId, teamId, installationId));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_INSTALLATION_IN_USE", exception.code());
        // 不删除任何数据
        verify(repositoryMapper, never()).delete(any(Wrapper.class));
        verify(installationMapper, never()).deleteById(any(GitHubInstallationEntity.class));
        // 不调用 GitHub 远程卸载 API
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void createInstallationUrlAlwaysReturnsNewInstallationPath() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        // 即使本地存在 Installation，服务层也不查询、不改 URL，始终返回 /new
        when(gitHubClient.createInstallationUrl(teamId, actorId, GitHubClient.WEB))
                .thenReturn("https://github.com/apps/qgents/installations/new?state=abc");

        var response = service.createInstallationUrl(actorId, teamId, GitHubClient.WEB);

        assertEquals("https://github.com/apps/qgents/installations/new?state=abc", response.getInstallationUrl());
        // 不查询本地 Installation（服务层只转发 client 生成的 URL）
        verify(installationMapper, never()).selectOne(any(Wrapper.class));
        verify(installationMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void createsRemoteRepositoryUsingTeamInstallation() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("Organization");
        installation.setAccountLogin("qgents-org");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(installation));

        when(gitHubClient.createRepository(anyLong(), anyString(), anyString(), any(GitHubRepositoryCreateRequest.class)))
                .thenReturn(new GitHubRepositoryDetails(9001L, "qgents-org", "new-repo", "main", "PRIVATE", false));

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("new-repo");
        request.setDescription("desc");
        request.setIsPrivate(true);

        GitHubRepositoryService.RemoteRepositoryCreation creation = service.createRemoteRepository(actorId, teamId, request);

        assertEquals(installation, creation.installation());
        assertEquals("new-repo", creation.repository().getName());
        assertEquals("main", creation.repository().getDefaultBranch());
    }

    @Test
    void createRemoteRepositoryRejectsWhenMultipleActiveInstallations() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity first = new GitHubInstallationEntity();
        first.setId(UUID.randomUUID());
        first.setProviderInstallationId(12345L);
        first.setStatus("ACTIVE");
        GitHubInstallationEntity second = new GitHubInstallationEntity();
        second.setId(UUID.randomUUID());
        second.setProviderInstallationId(67890L);
        second.setStatus("ACTIVE");
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(first, second));

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("new-repo");

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createRemoteRepository(actorId, teamId, request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("GITHUB_INSTALLATION_REQUIRED", exception.code());
        verify(gitHubClient, never()).createRepository(anyLong(), anyString(), anyString(),
                any(GitHubRepositoryCreateRequest.class));
    }

    @Test
    void bindsCreatedRepositoryMirrorAndProjectBinding() {
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setProviderInstallationId(12345L);
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(9001L, "qgents-org", "new-repo", "main",
                "PRIVATE", false);
        GitHubRepositoryService.RemoteRepositoryCreation creation =
                new GitHubRepositoryService.RemoteRepositoryCreation(installation, created);

        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(projectRepositoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("new-repo");
        request.setDisplayName("Backend Repo");

        ProjectRepositoryResponse response = service.bindCreatedRepository(projectId, creation, request);

        ArgumentCaptor<GitHubRepositoryEntity> mirror = ArgumentCaptor.forClass(GitHubRepositoryEntity.class);
        verify(repositoryMapper).insert(mirror.capture());
        assertEquals("new-repo", mirror.getValue().getName());
        assertEquals("main", mirror.getValue().getDefaultBranch());
        assertEquals("AUTHORIZED", mirror.getValue().getAuthorizationStatus());

        ArgumentCaptor<ProjectRepositoryEntity> binding = ArgumentCaptor.forClass(ProjectRepositoryEntity.class);
        verify(projectRepositoryMapper).insert(binding.capture());
        assertEquals(projectId, binding.getValue().getProjectId());
        assertEquals("Backend Repo", binding.getValue().getDisplayName());
        assertEquals("Backend Repo", response.getDisplayName());
        assertEquals("qgents-org/new-repo", response.getFullName());
    }
}
