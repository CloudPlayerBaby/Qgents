package qg.qgent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.github.GitHubAppClient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.worker.enabled=false",
                "spring.ai.openai.api-key=test-key",
                "sandbox.backend-service-token=test-token"
        })
public class GitCredentialServiceIntegrationTest {

    @Autowired
    private GitCredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GitHubAppClient githubAppClient;

    private final UUID teamId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID ghRepoId = UUID.randomUUID();
    private final UUID projectRepoId = UUID.randomUUID();
    private long providerInstallationId = 99999L;
    private String repoFullName = "test-owner/test-repo";
    private String headCommit = "abcdef123456";

    private byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return bytes;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256", e);
        }
    }

    @BeforeEach
    public void setup() {
        // Prepare the FK graph required by the grant secondary verification.
        jdbcTemplate.update("INSERT INTO users (id, email, display_name, password_hash, password_algorithm, status) VALUES (?, ?, 'Credential Test', 'unused', 'BCRYPT', 'ACTIVE')",
                toBytes(teamId), "credential-test-" + teamId + "@example.test");
        jdbcTemplate.update("INSERT INTO teams (id, owner_user_id, name, status) VALUES (?, ?, 'credential-test-team', 'ACTIVE')",
                toBytes(teamId), toBytes(teamId));
        jdbcTemplate.update("INSERT INTO projects (id, team_id, created_by, name, status) VALUES (?, ?, ?, 'credential-test-project', 'ACTIVE')",
                toBytes(projectId), toBytes(teamId), toBytes(teamId));
        jdbcTemplate.update("INSERT INTO github_installations (id, provider_installation_id, team_id, account_login, account_type, status, created_at, updated_at) VALUES (?, ?, ?, 'test-owner', 'USER', 'ACTIVE', NOW(), NOW())",
                toBytes(installationId), providerInstallationId, toBytes(teamId));

        jdbcTemplate.update("INSERT INTO github_repositories (id, installation_id, provider_repository_id, owner_login, name, default_branch, visibility, archived, authorization_status, synced_at) VALUES (?, ?, ?, 'test-owner', 'test-repo', 'main', 'PRIVATE', 0, 'AUTHORIZED', NOW())",
                toBytes(ghRepoId), toBytes(installationId), 88888L);

        jdbcTemplate.update("INSERT INTO project_repositories (id, project_id, repository_id, default_branch, display_name) VALUES (?, ?, ?, 'main', 'test-repo')",
                toBytes(projectRepoId), toBytes(projectId), toBytes(ghRepoId));
    }

    @AfterEach
    public void cleanup() {
        jdbcTemplate.update("DELETE FROM project_repositories WHERE id = ?", toBytes(projectRepoId));
        jdbcTemplate.update("DELETE FROM github_repositories WHERE id = ?", toBytes(ghRepoId));
        jdbcTemplate.update("DELETE FROM github_installations WHERE id = ?", toBytes(installationId));
        jdbcTemplate.update("DELETE FROM git_credential_grants WHERE team_id = ?", toBytes(teamId));
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", toBytes(projectId));
        jdbcTemplate.update("DELETE FROM teams WHERE id = ?", toBytes(teamId));
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", toBytes(teamId));
    }

    @Test
    public void testExchangeGrant_TransactionRollbackOnTokenFailure() {
        // 1. Generate grant
        String grantId = credentialService.generateGrant(teamId, projectId, providerInstallationId, repoFullName, "main", headCommit, GitCredentialPurpose.PUSH);
        String grantIdHash = sha256(grantId);

        // Verify initial state
        Boolean initialUsed = jdbcTemplate.queryForObject("SELECT is_used FROM git_credential_grants WHERE grant_id_hash = ?", Boolean.class, grantIdHash);
        Assertions.assertFalse(initialUsed, "Grant is_used should be false initially");

        // 2. Setup mock to throw exception on FIRST call, succeed on SECOND
        when(githubAppClient.createInstallationToken(providerInstallationId))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "TOKEN_ERROR", "Simulated GitHub API failure"))
                .thenReturn("ghs_success_token");

        // 3. First exchange attempt should fail
        ApiException ex = Assertions.assertThrows(ApiException.class, () -> {
            credentialService.exchangeGrant(grantId, headCommit, repoFullName, "main", GitCredentialPurpose.PUSH);
        });
        Assertions.assertEquals("TOKEN_ERROR", ex.code());

        // 4. Verify that is_used was rolled back to 0
        Boolean isUsedAfterFailure = jdbcTemplate.queryForObject("SELECT is_used FROM git_credential_grants WHERE grant_id_hash = ?", Boolean.class, grantIdHash);
        Assertions.assertFalse(isUsedAfterFailure, "Grant is_used should be false after transaction rollback");

        // 5. Second exchange attempt should succeed because is_used is still 0
        String token = credentialService.exchangeGrant(grantId, headCommit, repoFullName, "main", GitCredentialPurpose.PUSH);
        Assertions.assertEquals("ghs_success_token", token);

        // 6. Verify that is_used is now 1
        Boolean isUsedAfterSuccess = jdbcTemplate.queryForObject("SELECT is_used FROM git_credential_grants WHERE grant_id_hash = ?", Boolean.class, grantIdHash);
        Assertions.assertTrue(isUsedAfterSuccess, "Grant is_used should be true after successful exchange");
    }
}
