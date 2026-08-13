package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
