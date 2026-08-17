package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialGrant;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.mapper.GitCredentialGrantMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitCredentialServiceTest {

    @Mock
    private GitCredentialGrantMapper credentialGrantMapper;
    @Mock
    private GitHubAppClient githubAppClient;
    @Mock
    private qg.qgent.mapper.GitHubInstallationMapper installationMapper;
    @Mock
    private qg.qgent.mapper.GitHubRepositoryMapper gitHubRepositoryMapper;
    @Mock
    private qg.qgent.mapper.ProjectRepositoryMapper projectRepositoryMapper;

    private GitCredentialService service;

    private final UUID TEAM = UUID.randomUUID();
    private final UUID PROJECT = UUID.randomUUID();
    private final String HEAD = "abcdef";

    @BeforeEach
    void setUp() {
        service = new GitCredentialService(credentialGrantMapper, githubAppClient,
                installationMapper, gitHubRepositoryMapper, projectRepositoryMapper);
    }

    @Test
    void testGenerateGrant() {
        String grantId = service.generateGrant(TEAM, PROJECT, 123L, "owner/repo", "feat/xxx", HEAD);
        
        assertNotNull(grantId);
        assertFalse(grantId.isBlank());
        
        ArgumentCaptor<GitCredentialGrant> captor = ArgumentCaptor.forClass(GitCredentialGrant.class);
        verify(credentialGrantMapper, times(1)).insert(captor.capture());
        
        GitCredentialGrant saved = captor.getValue();
        assertEquals(TEAM, saved.getTeamId());
        assertEquals(123L, saved.getInstallationId());
        assertEquals(HEAD, saved.getExpectedHeadCommit());
        assertEquals(GitCredentialPurpose.PUSH, saved.getPurpose());
        assertFalse(saved.getIsUsed());
        assertNotNull(saved.getExpiresAt());
    }

    @Test
    void exchangeGrantSuccess() {
        String grantId = UUID.randomUUID().toString();
        
        when(credentialGrantMapper.exchangeGrant(anyString(), eq(HEAD), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any())).thenReturn(1);
        
        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setInstallationId(12345L);
        grant.setTeamId(TEAM);
        grant.setProjectId(PROJECT);
        grant.setRepositoryFullName("owner/repo");
        when(credentialGrantMapper.selectOne(any())).thenReturn(grant);

        qg.qgent.entity.GitHubInstallationEntity installation = new qg.qgent.entity.GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setStatus("ACTIVE");
        installation.setTeamId(TEAM);
        when(installationMapper.selectOne(any())).thenReturn(installation);

        qg.qgent.entity.GitHubRepositoryEntity repository = new qg.qgent.entity.GitHubRepositoryEntity();
        repository.setId(UUID.randomUUID());
        repository.setInstallationId(installation.getId());
        repository.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectOne(any())).thenReturn(repository);

        qg.qgent.entity.ProjectRepositoryEntity projectRepo = new qg.qgent.entity.ProjectRepositoryEntity();
        when(projectRepositoryMapper.selectOne(any())).thenReturn(projectRepo);
        
        when(githubAppClient.createInstallationToken(12345L)).thenReturn("ghs_token");

        String token = service.exchangeGrant(grantId, HEAD, "owner/repo", "main", GitCredentialPurpose.PUSH);
        assertEquals("ghs_token", token);
    }

    @Test
    void testExchangeGrantFailure_UsedOrExpired() {
        String grantId = UUID.randomUUID().toString();
        
        when(credentialGrantMapper.exchangeGrant(anyString(), eq("head"), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any(LocalDateTime.class))).thenReturn(0);
        
        ApiException exception = assertThrows(ApiException.class,
                () -> service.exchangeGrant(grantId, "head", "owner/repo", "main", GitCredentialPurpose.PUSH));
        assertEquals(HttpStatus.FORBIDDEN, exception.status());
    }
    @Test
    void testExchangeGrantFailure_InstallationRevoked() {
        String grantId = UUID.randomUUID().toString();
        when(credentialGrantMapper.exchangeGrant(anyString(), eq(HEAD), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any())).thenReturn(1);
        
        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setInstallationId(12345L);
        grant.setTeamId(TEAM);
        grant.setProjectId(PROJECT);
        grant.setRepositoryFullName("owner/repo");
        when(credentialGrantMapper.selectOne(any())).thenReturn(grant);

        qg.qgent.entity.GitHubInstallationEntity installation = new qg.qgent.entity.GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setStatus("SUSPENDED"); // not active
        when(installationMapper.selectOne(any())).thenReturn(installation);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.exchangeGrant(grantId, HEAD, "owner/repo", "main", GitCredentialPurpose.PUSH));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        // Since @Transactional is present on exchangeGrant, this exception will cause a rollback, leaving is_used=0.
    }

    @Test
    void exchangeGrantRejectsUnboundProjectRepository() {
        when(credentialGrantMapper.exchangeGrant(anyString(), eq(HEAD), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any())).thenReturn(1);
        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setInstallationId(12345L);
        grant.setTeamId(TEAM);
        grant.setProjectId(PROJECT);
        grant.setRepositoryFullName("owner/repo");
        when(credentialGrantMapper.selectOne(any())).thenReturn(grant);

        qg.qgent.entity.GitHubInstallationEntity installation = new qg.qgent.entity.GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setStatus("ACTIVE");
        installation.setTeamId(TEAM);
        when(installationMapper.selectOne(any())).thenReturn(installation);

        qg.qgent.entity.GitHubRepositoryEntity repository = new qg.qgent.entity.GitHubRepositoryEntity();
        repository.setId(UUID.randomUUID());
        repository.setInstallationId(installation.getId());
        repository.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectOne(any())).thenReturn(repository);
        when(projectRepositoryMapper.selectOne(any())).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.exchangeGrant(UUID.randomUUID().toString(), HEAD, "owner/repo", "main",
                        GitCredentialPurpose.PUSH));

        assertEquals("PROJECT_REPOSITORY_NOT_BOUND", exception.code());
        verifyNoInteractions(githubAppClient);
    }

    @Test
    void testExchangeGrantFailure_TokenGenerationFails() {
        String grantId = UUID.randomUUID().toString();
        when(credentialGrantMapper.exchangeGrant(anyString(), eq(HEAD), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any())).thenReturn(1);
        
        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setInstallationId(12345L);
        grant.setTeamId(TEAM);
        grant.setProjectId(PROJECT);
        grant.setRepositoryFullName("owner/repo");
        when(credentialGrantMapper.selectOne(any())).thenReturn(grant);

        qg.qgent.entity.GitHubInstallationEntity installation = new qg.qgent.entity.GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setStatus("ACTIVE");
        installation.setTeamId(TEAM);
        when(installationMapper.selectOne(any())).thenReturn(installation);

        qg.qgent.entity.GitHubRepositoryEntity repository = new qg.qgent.entity.GitHubRepositoryEntity();
        repository.setId(UUID.randomUUID());
        repository.setInstallationId(installation.getId());
        repository.setAuthorizationStatus("AUTHORIZED");
        when(gitHubRepositoryMapper.selectOne(any())).thenReturn(repository);

        qg.qgent.entity.ProjectRepositoryEntity projectRepo = new qg.qgent.entity.ProjectRepositoryEntity();
        when(projectRepositoryMapper.selectOne(any())).thenReturn(projectRepo);
        
        when(githubAppClient.createInstallationToken(12345L)).thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TOKEN_ERROR", "Token fails"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.exchangeGrant(grantId, HEAD, "owner/repo", "main", GitCredentialPurpose.PUSH));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.status());
        // Rolled back successfully!
    }
}
