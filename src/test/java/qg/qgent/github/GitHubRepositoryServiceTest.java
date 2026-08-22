package qg.qgent.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant; import java.time.LocalDateTime;
import java.util.List;
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
import qg.qgent.dto.CreateRemoteBranchRequest;
import qg.qgent.dto.GitHubInstallationResponse;
import qg.qgent.dto.NewProjectRepositoryRequest;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.dto.RemoteBranchResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TeamMemberMapper;
import qg.qgent.service.GitHubRepositoryService;
import qg.qgent.service.GitHubOAuthService;

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
    @Mock private TaskMapper taskMapper;
    @Mock private GitHubAppClient gitHubClient;
    @Mock private GitHubOAuthService githubOAuthService;
    @Mock private GitHubOAuthClient githubOAuthClient;

    private GitHubRepositoryService service;
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, qg.qgent.handler.UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "GitHubRepositoryServiceTest");
        TableInfoHelper.initTableInfo(assistant, GitHubInstallationEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectEntity.class);
    }

    @BeforeEach
    void setUp() {
        transactionManager = mock(PlatformTransactionManager.class);
        // lenient：仅 sync 相关测试会触发事务，其余测试该 stub 不被使用
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // lenient：仅解绑相关测试触发活动任务占用查询，默认无占用
        lenient().when(taskMapper.countActiveTasksUsingRepository(any(UUID.class))).thenReturn(0);
        service = new GitHubRepositoryService(installationMapper, repositoryMapper, projectRepositoryMapper,
                projectMapper, projectMemberMapper, teamMemberMapper, taskMapper, gitHubClient,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC), transactionManager);
    }

    private GitHubRepositoryService serviceWithOAuth() {
        return new GitHubRepositoryService(installationMapper, repositoryMapper, projectRepositoryMapper,
                projectMapper, projectMemberMapper, teamMemberMapper, taskMapper, gitHubClient,
                githubOAuthService, githubOAuthClient,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC), transactionManager);
    }

    @Test
    void bindsAuthorizedRepositoryUsingItsDefaultBranch() {
        GitHubRepositoryEntity repository = repository("main");
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(repository);
        when(projectRepositoryMapper.selectByProjectAndRepositoryForUpdate(projectId, repositoryId)).thenReturn(null);

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
        ProjectRepositoryEntity active = new ProjectRepositoryEntity();
        active.setStatus("ACTIVE");
        when(projectRepositoryMapper.selectByProjectAndRepositoryForUpdate(projectId, repositoryId)).thenReturn(active);

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
    void unbindsRepositoryByMarkingUnbound() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        binding.setStatus("ACTIVE");
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);

        service.unbindProjectRepository(actorId, projectId, projectRepositoryId);

        ArgumentCaptor<ProjectRepositoryEntity> captor = ArgumentCaptor.forClass(ProjectRepositoryEntity.class);
        verify(projectRepositoryMapper).updateById(captor.capture());
        assertEquals("UNBOUND", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getUnboundAt());
        verify(projectRepositoryMapper, never()).deleteById(any(UUID.class));
    }

    @Test
    void unbindIsIdempotentForAlreadyUnbound() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        binding.setStatus("UNBOUND");
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);

        service.unbindProjectRepository(actorId, projectId, projectRepositoryId);

        verify(projectRepositoryMapper, never()).updateById(any(ProjectRepositoryEntity.class));
    }

    @Test
    void unbindRejectedWhenRepositoryUsedByActiveTask() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        binding.setStatus("ACTIVE");
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);
        when(taskMapper.countActiveTasksUsingRepository(projectRepositoryId)).thenReturn(1);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.unbindProjectRepository(actorId, projectId, projectRepositoryId));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_IN_USE", exception.code());
        // 状态不变，不产生任何写入
        verify(projectRepositoryMapper, never()).updateById(any(ProjectRepositoryEntity.class));
    }

    @Test
    void updateProjectRepositoryRejectedWhenUnbound() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(projectRepositoryId);
        binding.setProjectId(projectId);
        binding.setStatus("UNBOUND");
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectById(projectRepositoryId)).thenReturn(binding);

        qg.qgent.dto.UpdateProjectRepositoryRequest request = new qg.qgent.dto.UpdateProjectRepositoryRequest();
        request.setDefaultBranch("main");
        request.setDisplayName("x");
        ApiException exception = assertThrows(ApiException.class,
                () -> service.updateProjectRepository(actorId, projectId, projectRepositoryId, request));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("PROJECT_REPOSITORY_UNBOUND", exception.code());
        verify(projectRepositoryMapper, never()).updateById(any(ProjectRepositoryEntity.class));
    }

    @Test
    void listsRealRemoteBranchesAndMarksProjectDefault() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = projectBinding(projectRepositoryId, "develop");
        GitHubRepositoryEntity repository = repository("main");
        GitHubInstallationEntity installation = activeInstallation();
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectById(projectRepositoryId)).thenReturn(binding);
        when(repositoryMapper.selectById(repositoryId)).thenReturn(repository);
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(gitHubClient.listBranches(12345L, "qgents", "backend")).thenReturn(java.util.List.of(
                new GitHubBranchDetails("develop", "develop-sha"),
                new GitHubBranchDetails("main", "main-sha")));

        List<RemoteBranchResponse> branches = service.listRemoteBranches(actorId, projectId, projectRepositoryId);

        assertEquals(2, branches.size());
        assertEquals("develop", branches.get(0).getName());
        assertEquals("develop-sha", branches.get(0).getCommitSha());
        assertEquals("main", branches.get(1).getName());
        assertEquals(true, branches.get(1).isGithubDefault());
        assertEquals(true, branches.get(0).isProjectDefault());
    }

    @Test
    void createsRemoteBranchFromResolvedSourceBranch() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = projectBinding(projectRepositoryId, "main");
        GitHubRepositoryEntity repository = repository("main");
        GitHubInstallationEntity installation = activeInstallation();
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectById(projectRepositoryId)).thenReturn(binding);
        when(repositoryMapper.selectById(repositoryId)).thenReturn(repository);
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(gitHubClient.getBranch(12345L, "qgents", "backend", "main"))
                .thenReturn(new GitHubBranchDetails("main", "main-sha"));
        when(gitHubClient.createBranch(12345L, "qgents", "backend", "develop", "main-sha"))
                .thenReturn(new GitHubBranchDetails("develop", "main-sha"));
        CreateRemoteBranchRequest request = new CreateRemoteBranchRequest();
        request.setName("develop");
        request.setFromRef("main");

        RemoteBranchResponse response = service.createRemoteBranch(actorId, projectId, projectRepositoryId, request);

        assertEquals("develop", response.getName());
        assertEquals("main-sha", response.getCommitSha());
        verify(gitHubClient).createBranch(12345L, "qgents", "backend", "develop", "main-sha");
    }

    @Test
    void validatesProjectDefaultBranchAgainstGitHubBeforeUpdating() {
        UUID projectRepositoryId = UUID.randomUUID();
        ProjectRepositoryEntity binding = projectBinding(projectRepositoryId, "main");
        GitHubRepositoryEntity repository = repository("main");
        GitHubInstallationEntity installation = activeInstallation();
        authorizeProjectAdmin();
        when(projectRepositoryMapper.selectById(projectRepositoryId)).thenReturn(binding);
        when(projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId)).thenReturn(binding);
        when(repositoryMapper.selectById(repositoryId)).thenReturn(repository);
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(gitHubClient.getBranch(12345L, "qgents", "backend", "develop"))
                .thenReturn(new GitHubBranchDetails("develop", "develop-sha"));
        qg.qgent.dto.UpdateProjectRepositoryRequest request = new qg.qgent.dto.UpdateProjectRepositoryRequest();
        request.setDefaultBranch(" develop ");
        request.setDisplayName("Backend");

        ProjectRepositoryResponse response = service.updateProjectRepository(actorId, projectId, projectRepositoryId,
                request);

        assertEquals("develop", response.getDefaultBranch());
        assertEquals("develop", binding.getDefaultBranch());
        verify(projectRepositoryMapper).updateById(binding);
    }

    @Test
    void rebindingRestoresUnboundRepositoryReusingId() {
        GitHubRepositoryEntity repository = repository("main");
        authorizeProjectAdmin();
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(repository);

        ProjectRepositoryEntity unbound = new ProjectRepositoryEntity();
        unbound.setId(UUID.randomUUID());
        unbound.setProjectId(projectId);
        unbound.setRepositoryId(repository.getId());
        unbound.setStatus("UNBOUND");
        when(projectRepositoryMapper.selectByProjectAndRepositoryForUpdate(projectId, repository.getId()))
                .thenReturn(unbound);

        ProjectRepositoryResponse response = service.bindProjectRepository(actorId, projectId,
                bindRequest(installationId, repositoryId, null, "Backend"));

        // 恢复使用显式 set 的条件更新（清空 unbound_at），不插入新行，复用原 id
        verify(projectRepositoryMapper).update(org.mockito.ArgumentMatchers.eq(unbound), any(Wrapper.class));
        assertEquals("ACTIVE", unbound.getStatus());
        assertNull(unbound.getUnboundAt());
        assertEquals("Backend", response.getDisplayName());
        verify(projectRepositoryMapper, never()).insert(any(ProjectRepositoryEntity.class));
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

        // 其他团队的 ACTIVE 安装且仍存在 AUTHORIZED 仓库：视为活跃占用，拒绝接管
        GitHubInstallationEntity existingInstallation = new GitHubInstallationEntity();
        existingInstallation.setId(UUID.randomUUID());
        existingInstallation.setTeamId(otherTeamId);
        existingInstallation.setProviderInstallationId(providerInstallationId);
        existingInstallation.setStatus("ACTIVE");
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(existingInstallation);
        when(repositoryMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.handleInstallationCallback(providerInstallationId, "mock_state"));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_INSTALLATION_TEAM_CONFLICT", exception.code());
    }

    @Test
    void takeoverIdleInstallationFromAnotherTeamRepointsAndReactivates() {
        long providerInstallationId = 12345L;
        UUID myTeamId = UUID.randomUUID();
        UUID otherTeamId = UUID.randomUUID();
        UUID existingInstallId = UUID.randomUUID();

        when(gitHubClient.verifyInstallationState("mock_state")).thenReturn(myTeamId);
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));

        // 其他团队的 DELETED 安装，其仓库已全部 REVOKED（无 AUTHORIZED）：视为闲置，允许接管
        GitHubInstallationEntity existingInstallation = new GitHubInstallationEntity();
        existingInstallation.setId(existingInstallId);
        existingInstallation.setTeamId(otherTeamId);
        existingInstallation.setProviderInstallationId(providerInstallationId);
        existingInstallation.setStatus("DELETED");
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(existingInstallation);

        UUID returnedTeamId = service.handleInstallationCallback(providerInstallationId, "mock_state");

        assertEquals(myTeamId, returnedTeamId);
        // 安装重新归属当前团队并恢复 ACTIVE
        ArgumentCaptor<GitHubInstallationEntity> installation = ArgumentCaptor.forClass(GitHubInstallationEntity.class);
        verify(installationMapper).updateById(installation.capture());
        assertEquals(existingInstallId, installation.getValue().getId());
        assertEquals(myTeamId, installation.getValue().getTeamId());
        assertEquals("ACTIVE", installation.getValue().getStatus());
        verify(repositoryMapper).insert(any(GitHubRepositoryEntity.class));
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
    void manualSyncMarksInstallationDeletedWhenGitHubReturnsNotFound() {
        UUID teamId = UUID.randomUUID();
        UUID localInstallationId = UUID.randomUUID();
        long providerInstallationId = 98765L;
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(localInstallationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(providerInstallationId);
        installation.setStatus("ACTIVE");
        when(installationMapper.selectOne(any(Wrapper.class))).thenReturn(installation);
        when(installationMapper.selectByProviderInstallationIdForUpdate(providerInstallationId))
                .thenReturn(installation);

        GitHubRepositoryEntity repository = repository("main");
        repository.setId(UUID.randomUUID());
        repository.setInstallationId(localInstallationId);
        repository.setAuthorizationStatus("AUTHORIZED");
        when(repositoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(repository));
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GITHUB_INSTALLATION_NOT_FOUND", "missing"));

        GitHubInstallationResponse response = service.manualSyncInstallation(actorId, teamId, localInstallationId);

        assertEquals("DELETED", response.getStatus());
        assertEquals("DELETED", installation.getStatus());
        assertEquals("REVOKED", repository.getAuthorizationStatus());
        verify(installationMapper).updateById(installation);
        verify(repositoryMapper).updateById(repository);
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
    void redirectsRepositoryTeamConflictToExistingCallbackConflictCode() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();
        UUID previousInstallationId = UUID.randomUUID();
        GitHubRepositoryEntity existingRepository = repository("main");
        existingRepository.setInstallationId(previousInstallationId);
        existingRepository.setProviderRepositoryId(100L);

        GitHubInstallationEntity previousInstallation = new GitHubInstallationEntity();
        previousInstallation.setId(previousInstallationId);
        previousInstallation.setTeamId(UUID.randomUUID());

        when(gitHubClient.verifyInstallationStateDetails("mock_state"))
                .thenReturn(new GitHubInstallationState(teamId, GitHubClient.WEB));
        when(gitHubClient.getInstallation(providerInstallationId))
                .thenReturn(new qg.qgent.github.GitHubInstallationDetails(
                        providerInstallationId, "qgents", "Organization"));
        when(gitHubClient.listRepositories(providerInstallationId)).thenReturn(java.util.List.of(
                new qg.qgent.github.GitHubRepositoryDetails(100L, "qgents", "repo1", "main", "PRIVATE", false)));
        when(installationMapper.selectByProviderInstallationIdForUpdate(anyLong())).thenReturn(null);
        when(installationMapper.selectById(previousInstallationId)).thenReturn(previousInstallation);
        when(repositoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(java.util.List.of(), java.util.List.of(existingRepository));

        GitHubInstallationState callbackState = service.handleInstallationCallbackDetails(providerInstallationId,
                "mock_state");

        assertEquals("GITHUB_INSTALLATION_TEAM_CONFLICT", callbackState.conflictCode());
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
    void syncReactivatesDeletedInstallationWhenGitHubConfirmsItLive() {
        long providerInstallationId = 12345L;
        UUID teamId = UUID.randomUUID();

        // 本请求刚成功拉取 GitHub 实时快照：安装已重新授权，不是陈旧快照，允许恢复 ACTIVE
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

        // DELETED 且 GitHub 确认 live：恢复 ACTIVE 并重新同步仓库
        ArgumentCaptor<GitHubInstallationEntity> installation = ArgumentCaptor.forClass(GitHubInstallationEntity.class);
        verify(installationMapper).updateById(installation.capture());
        assertEquals("ACTIVE", installation.getValue().getStatus());
        verify(repositoryMapper).insert(any(GitHubRepositoryEntity.class));
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

    private ProjectRepositoryEntity projectBinding(UUID id, String defaultBranch) {
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(id);
        binding.setProjectId(projectId);
        binding.setRepositoryId(repositoryId);
        binding.setDefaultBranch(defaultBranch);
        binding.setStatus("ACTIVE");
        return binding;
    }

    private GitHubInstallationEntity activeInstallation() {
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(UUID.randomUUID());
        installation.setProviderInstallationId(12345L);
        installation.setStatus("ACTIVE");
        return installation;
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
    void createsAndBindsRepositoryToExistingProject() {
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = activeInstallation();
        installation.setTeamId(teamId);
        installation.setAccountType("Organization");
        installation.setAccountLogin("qgents");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                987L, "qgents", "new-repository", "main", "PRIVATE", false);
        when(gitHubClient.createRepository(eq(12345L), eq("Organization"), eq("qgents"), any()))
                .thenReturn(created);
        when(repositoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(projectRepositoryMapper.selectByProjectAndRepositoryForUpdate(eq(projectId), any(UUID.class)))
                .thenReturn(null);

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("new-repository");
        request.setInstallationId(installationId);
        request.setDisplayName("新仓库");

        ProjectRepositoryResponse response = service.createProjectRepository(actorId, projectId, request);

        assertNotNull(response);
        assertEquals("qgents/new-repository", response.getFullName());
        assertEquals("main", response.getDefaultBranch());
        verify(gitHubClient).createRepository(eq(12345L), eq("Organization"), eq("qgents"), any());
        verify(repositoryMapper).insert(any(GitHubRepositoryEntity.class));
        verify(projectRepositoryMapper).insert(any(ProjectRepositoryEntity.class));
        verify(gitHubClient, never()).deleteRepository(anyLong(), anyString(), anyString());
    }

    @Test
    void projectRepositoryCreationRequiresTeamOwner() {
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("new-repository");
        request.setInstallationId(installationId);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createProjectRepository(actorId, projectId, request));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        assertEquals("GITHUB_REPOSITORY_ACCESS_DENIED", exception.code());
        verifyNoInteractions(gitHubClient);
    }

    @Test
    void rejectsAndCleansUpCreatedRepositoryWithoutDefaultBranch() {
        UUID teamId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);

        GitHubInstallationEntity installation = activeInstallation();
        installation.setTeamId(teamId);
        installation.setAccountType("Organization");
        installation.setAccountLogin("qgents");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                988L, "qgents", "empty-repository", null, "PRIVATE", false);
        when(gitHubClient.createRepository(eq(12345L), eq("Organization"), eq("qgents"), any()))
                .thenReturn(created);

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("empty-repository");
        request.setInstallationId(installationId);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createProjectRepository(actorId, projectId, request));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.status());
        assertEquals("GITHUB_REPOSITORY_METADATA_INCOMPLETE", exception.code());
        verify(gitHubClient).deleteRepository(12345L, "qgents", "empty-repository");
        verifyNoInteractions(repositoryMapper, projectRepositoryMapper);
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
    void rejectsPersonalRepositoryCreationBeforeCallingGitHubApp() {
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(installation));

        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName("personal-repo");

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createRemoteRepository(actorId, teamId, request));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.status());
        assertEquals("GITHUB_PERSONAL_REPOSITORY_CREATION_NOT_SUPPORTED", exception.code());
        verify(gitHubClient, never()).createRepository(anyLong(), anyString(), anyString(),
                any(GitHubRepositoryCreateRequest.class));
    }

    @Test
    void personalRepositoryCreationRequiresAppVisibilityBeforeReturning() {
        service = serviceWithOAuth();
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(githubOAuthService.requirePersonalCredential(actorId)).thenReturn(
                new GitHubOAuthService.PersonalCredential("oauth-token", 77L, "personal-user", List.of("repo")));
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                7001L, "personal-user", "personal-repo", "main", "PRIVATE", false);
        when(githubOAuthClient.createPersonalRepository(eq("oauth-token"), any())).thenReturn(created);
        when(gitHubClient.listRepositories(12345L)).thenReturn(List.of(), List.of(
                new GitHubRepositoryDetails(7001L, "personal-user", "personal-repo", "main", "PRIVATE", false)));

        GitHubRepositoryService.RemoteRepositoryCreation result = service.createRemoteRepository(actorId, teamId,
                newRepositoryRequest("personal-repo", installationId));

        assertEquals(created, result.repository());
        verify(gitHubClient, org.mockito.Mockito.times(2)).listRepositories(12345L);
        verify(githubOAuthClient, never()).deletePersonalRepository(anyString(), anyString(), anyString());
    }

    @Test
    void personalRepositoryCreationRejectsWhenAppCannotSeeRepositoryAndCompensates() {
        service = serviceWithOAuth();
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(githubOAuthService.requirePersonalCredential(actorId)).thenReturn(
                new GitHubOAuthService.PersonalCredential("oauth-token", 77L, "personal-user", List.of("repo")));
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                7002L, "personal-user", "personal-repo", "main", "PRIVATE", false);
        when(githubOAuthClient.createPersonalRepository(eq("oauth-token"), any())).thenReturn(created);
        when(gitHubClient.listRepositories(12345L)).thenReturn(List.of());

        ApiException exception = assertThrows(ApiException.class, () -> service.createRemoteRepository(actorId, teamId,
                newRepositoryRequest("personal-repo", installationId)));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        assertEquals("GITHUB_REPOSITORY_NOT_AUTHORIZED", exception.code());
        verify(githubOAuthClient).deletePersonalRepository("oauth-token", "personal-user", "personal-repo");
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
    }

    @Test
    void personalRepositoryCreationRejectsOwnerMismatchBeforeAppVisibilityCheck() {
        service = serviceWithOAuth();
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(githubOAuthService.requirePersonalCredential(actorId)).thenReturn(
                new GitHubOAuthService.PersonalCredential("oauth-token", 77L, "personal-user", List.of("repo")));
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                7003L, "another-user", "personal-repo", "main", "PRIVATE", false);
        when(githubOAuthClient.createPersonalRepository(eq("oauth-token"), any())).thenReturn(created);

        ApiException exception = assertThrows(ApiException.class, () -> service.createRemoteRepository(actorId, teamId,
                newRepositoryRequest("personal-repo", installationId)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("GITHUB_OAUTH_ACCOUNT_MISMATCH", exception.code());
        verify(gitHubClient, never()).listRepositories(anyLong());
        verify(githubOAuthClient).deletePersonalRepository("oauth-token", "another-user", "personal-repo");
    }

    @Test
    void personalRepositoryCreationMarksInvalidWhenGitHubRejects401() {
        service = serviceWithOAuth();
        UUID teamId = UUID.randomUUID();
        when(teamMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(12345L);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        installation.setStatus("ACTIVE");
        when(installationMapper.selectById(installationId)).thenReturn(installation);
        when(githubOAuthService.requirePersonalCredential(actorId)).thenReturn(
                new GitHubOAuthService.PersonalCredential("oauth-token", 77L, "personal-user", List.of("repo")));
        when(githubOAuthClient.createPersonalRepository(eq("oauth-token"), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_REVOKED",
                        "GitHub OAuth 授权已失效，请重新授权"));

        ApiException exception = assertThrows(ApiException.class, () -> service.createRemoteRepository(actorId, teamId,
                newRepositoryRequest("personal-repo", installationId)));

        assertEquals("GITHUB_OAUTH_REVOKED", exception.code());
        verify(githubOAuthService).markInvalid(actorId, "GITHUB_OAUTH_REVOKED");
        verify(gitHubClient, never()).listRepositories(anyLong());
        verify(githubOAuthClient, never()).deletePersonalRepository(anyString(), anyString(), anyString());
    }

    @Test
    void deleteRemoteRepositoryUsesOperationTokenForPersonalRepository() {
        service = serviceWithOAuth();
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                7004L, "personal-user", "personal-repo", "main", "PRIVATE", false);
        GitHubRepositoryService.RemoteRepositoryCreation creation =
                new GitHubRepositoryService.RemoteRepositoryCreation(actorId, installation, created, "operation-token");

        service.deleteRemoteRepository(creation);

        verify(githubOAuthClient).deletePersonalRepository("operation-token", "personal-user", "personal-repo");
        verify(githubOAuthService, never()).requirePersonalCredential(any());
    }

    @Test
    void deleteRemoteRepositoryFallsBackToCurrentCredentialWhenNoOperationToken() {
        service = serviceWithOAuth();
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setAccountType("USER");
        installation.setAccountLogin("personal-user");
        GitHubRepositoryDetails created = new GitHubRepositoryDetails(
                7005L, "personal-user", "personal-repo", "main", "PRIVATE", false);
        GitHubRepositoryService.RemoteRepositoryCreation creation =
                new GitHubRepositoryService.RemoteRepositoryCreation(actorId, installation, created);
        when(githubOAuthService.requirePersonalCredential(actorId)).thenReturn(
                new GitHubOAuthService.PersonalCredential("current-token", 77L, "personal-user", List.of("repo")));

        service.deleteRemoteRepository(creation);

        verify(githubOAuthClient).deletePersonalRepository("current-token", "personal-user", "personal-repo");
    }

    private NewProjectRepositoryRequest newRepositoryRequest(String name, UUID requestedInstallationId) {
        NewProjectRepositoryRequest request = new NewProjectRepositoryRequest();
        request.setName(name);
        request.setInstallationId(requestedInstallationId);
        return request;
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
        // 自动建仓的镜像 id 是新分配的，与请求参数无关，需按任意参数匹配
        lenient().when(projectRepositoryMapper.selectByProjectAndRepositoryForUpdate(any(UUID.class), any(UUID.class)))
                .thenReturn(null);

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
