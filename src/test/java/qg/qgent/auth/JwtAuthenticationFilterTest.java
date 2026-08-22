package qg.qgent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    @Test
    void skipsInternalServiceEndpoints() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(mock(TokenService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/git-credentials/exchange");
        request.setServletPath("/internal/v1/git-credentials/exchange");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void stillFiltersPublicApiEndpoints() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(mock(TokenService.class), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setServletPath("/api/v1/projects");

        assertFalse(filter.shouldNotFilter(request));
    }
}
