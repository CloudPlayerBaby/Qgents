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
    private GitCredentialGrantMapper mapper;

    @Mock
    private GitHubAppClient githubAppClient;

    private GitCredentialService service;

    @BeforeEach
    void setUp() {
        service = new GitCredentialService(mapper, githubAppClient);
    }

    @Test
    void testGenerateGrant() {
        UUID teamId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        
        String grantId = service.generateGrant(teamId, projectId, 123L, "owner/repo", "feat/xxx", "abcdef");
        
        assertNotNull(grantId);
        assertFalse(grantId.isBlank());
        
        ArgumentCaptor<GitCredentialGrant> captor = ArgumentCaptor.forClass(GitCredentialGrant.class);
        verify(mapper, times(1)).insert(captor.capture());
        
        GitCredentialGrant saved = captor.getValue();
        assertEquals(teamId, saved.getTeamId());
        assertEquals(123L, saved.getInstallationId());
        assertEquals("abcdef", saved.getExpectedHeadCommit());
        assertEquals(GitCredentialPurpose.PUSH, saved.getPurpose());
        assertFalse(saved.getIsUsed());
        assertNotNull(saved.getExpiresAt());
    }

    @Test
    void testExchangeGrantSuccess() {
        String grantId = UUID.randomUUID().toString();
        String expectedHead = "abcdef";
        
        when(mapper.exchangeGrant(anyString(), eq(expectedHead), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.FETCH), any(LocalDateTime.class))).thenReturn(1);
        
        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setInstallationId(456L);
        when(mapper.selectOne(any())).thenReturn(grant);
        
        when(githubAppClient.createInstallationToken(456L)).thenReturn("ghs_token123");
        
        String token = service.exchangeGrant(grantId, expectedHead, "owner/repo", "main", GitCredentialPurpose.FETCH);
        assertEquals("ghs_token123", token);
    }

    @Test
    void testExchangeGrantFailure_UsedOrExpired() {
        String grantId = UUID.randomUUID().toString();
        
        when(mapper.exchangeGrant(anyString(), eq("head"), eq("owner/repo"), eq("main"),
                eq(GitCredentialPurpose.PUSH), any(LocalDateTime.class))).thenReturn(0);
        
        ApiException exception = assertThrows(ApiException.class,
                () -> service.exchangeGrant(grantId, "head", "owner/repo", "main", GitCredentialPurpose.PUSH));
        assertEquals(HttpStatus.FORBIDDEN, exception.status());
    }
}
