package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

class IdempotencyFilterTest {

    @Test
    void filtersGitHubInstallationEndpoints() {
        IdempotencyFilter filter = new IdempotencyFilter(null, null);

        // POST installations requires idempotency
        MockHttpServletRequest request1 = new MockHttpServletRequest("POST", "/api/v1/teams/team-1/integrations/github/installations");
        assertFalse(filter.shouldNotFilter(request1));

        // POST manual sync requires idempotency
        MockHttpServletRequest request2 = new MockHttpServletRequest("POST", "/api/v1/teams/team-1/integrations/github/installations/inst-1/sync");
        assertFalse(filter.shouldNotFilter(request2));

        // GET installations does NOT require idempotency
        MockHttpServletRequest request3 = new MockHttpServletRequest("GET", "/api/v1/teams/team-1/integrations/github/installations");
        assertTrue(filter.shouldNotFilter(request3));

        // DELETE installation requires idempotency (filtered)
        MockHttpServletRequest request4 = new MockHttpServletRequest("DELETE", "/api/v1/teams/team-1/integrations/github/installations/inst-1");
        assertFalse(filter.shouldNotFilter(request4)); // DELETE installation IS filtered
    }

    @Test
    void filtersProjectRepositoryEndpoints() {
        IdempotencyFilter filter = new IdempotencyFilter(null, null);

        // POST project repository requires idempotency
        MockHttpServletRequest request1 = new MockHttpServletRequest("POST", "/api/v1/projects/proj-1/repositories");
        assertFalse(filter.shouldNotFilter(request1));

        // PATCH project repository requires idempotency
        MockHttpServletRequest request2 = new MockHttpServletRequest("PATCH", "/api/v1/projects/proj-1/repositories/repo-1");
        assertFalse(filter.shouldNotFilter(request2));

        // DELETE project repository requires idempotency
        MockHttpServletRequest request3 = new MockHttpServletRequest("DELETE", "/api/v1/projects/proj-1/repositories/repo-1");
        assertFalse(filter.shouldNotFilter(request3));
    }

    @Test
    void filtersAllProjectWriteEndpointsIncludingPreflightAndMergeRequestCreation() {
        IdempotencyFilter filter = new IdempotencyFilter(null, null);

        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/projects/proj-1/dry-runs")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/projects/proj-1/dry-runs/run-1/cq-approvals")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/projects/proj-1/merge-requests")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("PATCH", "/api/v1/projects/proj-1/tasks/task-1")));
    }

    @Test
    void cachesEmptyBodyFor204Response() throws ServletException, IOException {
        qg.qgent.service.IdempotencyService mockService = mock(qg.qgent.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/projects/proj-1/repositories/repo-1");
        request.addHeader("Idempotency-Key", "key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        FilterChain chain = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
        };

        filter.doFilterInternal(request, response, chain);

        verify(mockService).save(eq(userId), any(), anyString(), eq("key-123"), any(), eq(204), eq(Map.of()), isNull());
    }

    @Test
    void replays204WithoutExecutingFilterChain() throws ServletException, IOException, Exception {
        qg.qgent.service.IdempotencyService mockService = mock(qg.qgent.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/projects/proj-1/repositories/repo-1");
        request.addHeader("Idempotency-Key", "key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        // Mock existing record
        qg.qgent.entity.IdempotencyRecordEntity existing = new qg.qgent.entity.IdempotencyRecordEntity();
        existing.setResponseStatus(204);
        existing.setResponseBodyRedacted(Map.of());
        existing.setRequestHash(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        
        when(mockService.find(any(), anyString(), eq("key-123"))).thenReturn(existing);

        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // verify that the chain was NEVER executed
        verify(chain, never()).doFilter(any(), any());

        // verify response status is 204
        org.junit.jupiter.api.Assertions.assertEquals(204, response.getStatus());
    }

    @Test
    void replaysLargeBodyWithPrefixHashMatchingCachedPrefix() throws Exception {
        qg.qgent.service.IdempotencyService mockService = mock(qg.qgent.service.IdempotencyService.class);
        ObjectMapper mapper = new ObjectMapper();
        IdempotencyFilter filter = new IdempotencyFilter(mockService, mapper);

        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );

        // 超过 1MB 缓存上限的原始字节（模拟代理上传大文件）
        byte[] large = new byte[1024 * 1024 + 64];
        Arrays.fill(large, (byte) 'a');

        // 首次请求：204，幂等记录里保存的是前 1MB 前缀的哈希
        MockHttpServletRequest first = new MockHttpServletRequest("PUT",
                "/api/v1/projects/proj-1/attachments/att-1");
        first.addHeader("Idempotency-Key", "key-123");
        first.setContent(large);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        // 还原真实链路：下游控制器会读取整个请求体，从而填满 ContentCachingRequestWrapper 的 1MB 缓存
        filter.doFilterInternal(first, firstResponse, (req, res) -> {
            req.getInputStream().readAllBytes();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
        });

        ArgumentCaptor<byte[]> hashCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockService).save(eq(userId), any(), anyString(), eq("key-123"), hashCaptor.capture(),
                eq(204), eq(Map.of()), isNull());

        // 同键同 body 重试：应回放 204，且不因「全量哈希 vs 前缀哈希」不一致误报 409
        qg.qgent.entity.IdempotencyRecordEntity existing = new qg.qgent.entity.IdempotencyRecordEntity();
        existing.setResponseStatus(204);
        existing.setResponseBodyRedacted(Map.of());
        existing.setRequestHash(hashCaptor.getValue());
        when(mockService.find(any(), anyString(), eq("key-123"))).thenReturn(existing);

        MockHttpServletRequest retry = new MockHttpServletRequest("PUT",
                "/api/v1/projects/proj-1/attachments/att-1");
        retry.addHeader("Idempotency-Key", "key-123");
        retry.setContent(large);
        MockHttpServletResponse retryResponse = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(retry, retryResponse, chain);

        verify(chain, never()).doFilter(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(204, retryResponse.getStatus());
    }
}
