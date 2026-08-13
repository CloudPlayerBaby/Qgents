package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
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
}
