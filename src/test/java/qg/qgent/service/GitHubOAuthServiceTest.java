package qg.qgent.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubOAuthProperties;
import qg.qgent.dto.GitHubOAuthStatusResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubOAuthStateEntity;
import qg.qgent.entity.GitHubUserAuthorizationEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubOAuthClient;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubOAuthStateMapper;
import qg.qgent.mapper.GitHubUserAuthorizationMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubOAuthServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC);

    @Mock private GitHubOAuthClient client;
    @Mock private GitHubOAuthStateMapper stateMapper;
    @Mock private GitHubUserAuthorizationMapper authorizationMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private GitHubInstallationMapper installationMapper;

    private GitHubOAuthProperties properties;
    private GitHubOAuthTokenCipher cipher;
    private GitHubOAuthService service;

    @BeforeAll
    static void initializeMyBatisPlusMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, qg.qgent.handler.UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "GitHubOAuthServiceTest");
        TableInfoHelper.initTableInfo(assistant, GitHubInstallationEntity.class);
    }

    @BeforeEach
    void setUp() {
        properties = new GitHubOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setCallbackUrl("https://qgents.example.com/oauth/callback");
        properties.setStateSecret("state-secret");
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new GitHubOAuthTokenCipher(properties);
        service = new GitHubOAuthService(properties, client, stateMapper, authorizationMapper,
                teamMemberMapper, installationMapper, cipher, CLOCK);
    }

    @Test
    void statusComputesPrivateCapabilityFromRepoScope() {
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(UUID.randomUUID(), 77L, "octocat", "ACTIVE", "repo"));

        GitHubOAuthStatusResponse response = service.status(UUID.randomUUID());

        assertTrue(response.isAuthorized());
        assertTrue(response.isCanCreatePublicPersonalRepository());
        assertTrue(response.isCanCreatePrivatePersonalRepository());
    }

    @Test
    void statusReportsOnlyPublicCapabilityForPublicRepoScope() {
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(UUID.randomUUID(), 77L, "octocat", "ACTIVE", "public_repo,read:user"));

        GitHubOAuthStatusResponse response = service.status(UUID.randomUUID());

        assertTrue(response.isAuthorized());
        assertTrue(response.isCanCreatePublicPersonalRepository());
        assertFalse(response.isCanCreatePrivatePersonalRepository());
    }

    @Test
    void statusReturnsUnauthorizedWhenNotActive() {
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(UUID.randomUUID(), 77L, "octocat", "REVOKED", "repo"));

        GitHubOAuthStatusResponse response = service.status(UUID.randomUUID());

        assertFalse(response.isAuthorized());
        assertFalse(response.isCanCreatePublicPersonalRepository());
        assertFalse(response.isCanCreatePrivatePersonalRepository());
    }

    @Test
    void setupReportsNotOwnerWhenUserOwnsNoTeam() {
        UUID userId = UUID.randomUUID();
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of());

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("NOT_OWNER", response.getPersonalRepositorySetup());
    }

    @Test
    void setupReportsNeedInstallationWhenNoUserInstallation() {
        UUID userId = UUID.randomUUID();
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of(ownerMember(userId)));
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("NEED_INSTALLATION", response.getPersonalRepositorySetup());
    }

    @Test
    void setupReportsNeedOAuthWhenUserInstallationExistsButNotAuthorized() {
        UUID userId = UUID.randomUUID();
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of(ownerMember(userId)));
        when(installationMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(userInstallation(UUID.randomUUID(), "octocat")));
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("NEED_OAUTH", response.getPersonalRepositorySetup());
    }

    @Test
    void setupReportsAccountMismatchAndExpectedLogin() {
        UUID userId = UUID.randomUUID();
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of(ownerMember(userId)));
        when(installationMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(userInstallation(UUID.randomUUID(), "octocat")));
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(userId, 77L, "another-user", "ACTIVE", "repo"));

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("ACCOUNT_MISMATCH", response.getPersonalRepositorySetup());
        assertEquals("octocat", response.getExpectedInstallationLogin());
    }

    @Test
    void setupReportsReadyWhenLoginMatches() {
        UUID userId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of(ownerMember(userId)));
        when(installationMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(userInstallation(installationId, "octocat")));
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(userId, 77L, "octocat", "ACTIVE", "repo"));

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("READY", response.getPersonalRepositorySetup());
        assertEquals(installationId, response.getUserInstallationId());
        assertEquals("ALL", response.getRepositoryAccessScope());
    }

    @Test
    void setupBlocksSelectedRepositoryInstallation() {
        UUID userId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        GitHubInstallationEntity installation = userInstallation(installationId, "octocat");
        installation.setRepositorySelection("SELECTED");
        when(teamMemberMapper.selectByUserId(userId)).thenReturn(List.of(ownerMember(userId)));
        when(installationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(installation));
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(userId, 77L, "octocat", "ACTIVE", "repo"));

        GitHubOAuthStatusResponse response = service.status(userId);

        assertEquals("NEED_ALL_REPOSITORIES_ACCESS", response.getPersonalRepositorySetup());
        assertEquals(installationId, response.getUserInstallationId());
        assertEquals("SELECTED", response.getRepositoryAccessScope());
    }

    @Test
    void revokeClaimsRevokingThenMarksRevokedOnSuccess() {
        UUID userId = UUID.randomUUID();
        GitHubUserAuthorizationEntity entity = entity(userId, 77L, "octocat", "ACTIVE", "repo");
        entity.setAccessTokenCiphertext(cipher.encrypt("user-token"));
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);
        when(authorizationMapper.claimRevoking(eq(userId), any())).thenReturn(1);

        service.revoke(userId);

        verify(client).revokeAccessToken("user-token");
        verify(authorizationMapper).markRevoked(eq(entity.getId()), any());
        verify(authorizationMapper, never()).markRevokeFailed(any(), anyString(), any());
    }

    @Test
    void revokeMarksErrorAndRethrowsWhenRemoteRejects() {
        UUID userId = UUID.randomUUID();
        GitHubUserAuthorizationEntity entity = entity(userId, 77L, "octocat", "ACTIVE", "repo");
        entity.setAccessTokenCiphertext(cipher.encrypt("user-token"));
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);
        when(authorizationMapper.claimRevoking(eq(userId), any())).thenReturn(1);
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "unavailable"))
                .when(client).revokeAccessToken("user-token");

        assertThrows(ApiException.class, () -> service.revoke(userId));

        verify(authorizationMapper).markRevokeFailed(eq(entity.getId()), eq("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE"), any());
        verify(authorizationMapper, never()).markRevoked(any(), any());
    }

    @Test
    void revokeIsNoOpWhenAnotherRequestAlreadyClaimed() {
        UUID userId = UUID.randomUUID();
        GitHubUserAuthorizationEntity entity = entity(userId, 77L, "octocat", "ACTIVE", "repo");
        entity.setAccessTokenCiphertext(cipher.encrypt("user-token"));
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(entity);
        when(authorizationMapper.claimRevoking(eq(userId), any())).thenReturn(0);

        service.revoke(userId);

        verify(client, never()).revokeAccessToken(anyString());
        verify(authorizationMapper, never()).markRevoked(any(), any());
        verify(authorizationMapper, never()).markRevokeFailed(any(), anyString(), any());
    }

    @Test
    void markInvalidDelegatesToConditionalUpdate() {
        UUID userId = UUID.randomUUID();

        service.markInvalid(userId, "GITHUB_OAUTH_REVOKED");

        verify(authorizationMapper).markInvalid(eq(userId), eq("GITHUB_OAUTH_REVOKED"), any());
    }

    @Test
    void requirePersonalCredentialRejectsNonActiveAuthorization() {
        UUID userId = UUID.randomUUID();
        when(authorizationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(entity(userId, 77L, "octocat", "REVOKED", "repo"));

        ApiException exception = assertThrows(ApiException.class, () -> service.requirePersonalCredential(userId));
        assertEquals("GITHUB_OAUTH_REVOKED", exception.code());
    }

    @Test
    void requirePersonalCredentialRejectsMissingAuthorizationWithActionableCode() {
        UUID userId = UUID.randomUUID();
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ApiException exception = assertThrows(ApiException.class, () -> service.requirePersonalCredential(userId));
        assertEquals("GITHUB_OAUTH_REQUIRED", exception.code());
    }

    @Test
    void callbackReconcilesDuplicateKeyOnConcurrentSave() {
        UUID userId = UUID.randomUUID();
        UUID stateId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        GitHubOAuthStateEntity state = new GitHubOAuthStateEntity();
        state.setId(stateId);
        state.setUserId(userId);
        state.setClient("WEB");
        state.setExpiresAt(now.plusSeconds(600));
        // JWT 签名/过期校验使用真实系统时钟（JWT 库不注入 Clock），因此用真实时间构造 state，
        // 而实体过期时间与 consumeState 的 now() 保持一致使用固定 CLOCK。
        Instant realNow = Instant.now();
        String stateValue = buildState(stateId, userId, "WEB", realNow, realNow.plusSeconds(600));
        state.setStateHash(sha256(stateValue));

        when(stateMapper.selectOne(any(Wrapper.class))).thenReturn(state);
        when(stateMapper.consume(eq(stateId), any())).thenReturn(1);
        when(client.exchangeCode("the-code")).thenReturn(new GitHubOAuthClient.OAuthToken("user-token", List.of("repo")));
        when(client.getCurrentUser("user-token")).thenReturn(new GitHubOAuthClient.GitHubUser(77L, "octocat"));

        GitHubUserAuthorizationEntity existing = entity(userId, 77L, "octocat", "ACTIVE", "repo");
        existing.setId(UUID.randomUUID());
        // 首次 findByProvider / findByUser 均为空，insert 冲突后重读 findByProvider 为空、findByUser 返回已有记录。
        when(authorizationMapper.selectOne(any(Wrapper.class))).thenReturn(null, null, null, existing);
        doThrow(new DuplicateKeyException("duplicate key")).when(authorizationMapper)
                .insert(any(GitHubUserAuthorizationEntity.class));

        GitHubOAuthService.CallbackResult result = service.callback("the-code", stateValue, null);

        assertEquals("WEB", result.client());
        verify(authorizationMapper).updateById(existing);
        assertEquals("ACTIVE", existing.getStatus());
        assertEquals("user-token", cipher.decrypt(existing.getAccessTokenCiphertext()));
    }

    private GitHubUserAuthorizationEntity entity(UUID userId, long providerUserId, String login, String status,
                                                 String scopes) {
        GitHubUserAuthorizationEntity entity = new GitHubUserAuthorizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setProvider("GITHUB");
        entity.setProviderUserId(providerUserId);
        entity.setProviderLogin(login);
        entity.setScopes(scopes);
        entity.setStatus(status);
        return entity;
    }

    private TeamMemberEntity ownerMember(UUID userId) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setUserId(userId);
        member.setTeamId(UUID.randomUUID());
        member.setRole("TEAM_OWNER");
        return member;
    }

    private GitHubInstallationEntity userInstallation(UUID installationId, String login) {
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(installationId);
        installation.setTeamId(UUID.randomUUID());
        installation.setAccountLogin(login);
        installation.setAccountType("USER");
        installation.setRepositorySelection("ALL");
        installation.setStatus("ACTIVE");
        return installation;
    }

    private String buildState(UUID stateId, UUID userId, String clientType, Instant issuedAt, Instant expiresAt) {
        return JWT.create().withIssuer("qgents-github-oauth")
                .withJWTId(stateId.toString()).withSubject(userId.toString())
                .withClaim("client", clientType)
                .withIssuedAt(issuedAt)
                .withExpiresAt(expiresAt)
                .sign(Algorithm.HMAC256(properties.getStateSecret()));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
