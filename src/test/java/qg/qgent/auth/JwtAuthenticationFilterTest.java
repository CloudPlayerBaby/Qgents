package qg.qgent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.core.env.Environment;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @Test
    void skipsInternalServiceEndpoints() {
        JwtAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/git-credentials/exchange");
        request.setServletPath("/internal/v1/git-credentials/exchange");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void stillFiltersPublicApiEndpoints() {
        JwtAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setServletPath("/api/v1/projects");

        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    void expiredAccessCookieDoesNotBlockPublicRefreshEndpoint() throws Exception {
        TokenService tokens = mock(TokenService.class);
        when(tokens.verifyAccess("expired")).thenReturn(null);
        JwtAuthenticationFilter filter = filter(tokens, true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setCookies(new Cookie(AuthCookieService.ACCESS_COOKIE, "expired"));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(chain.getRequest() != null);
    }

    @Test
    void cookieOnlyPhaseDoesNotReadBearerHeader() throws Exception {
        TokenService tokens = mock(TokenService.class);
        JwtAuthenticationFilter filter = filter(tokens, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader("Authorization", "Bearer legacy-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(tokens, never()).verifyAccess("legacy-token");
    }

    private JwtAuthenticationFilter filter() {
        return filter(mock(TokenService.class), true);
    }

    private JwtAuthenticationFilter filter(TokenService tokens, boolean legacyTokenCompatibility) {
        return new JwtAuthenticationFilter(tokens, new ObjectMapper(),
                new AuthCookieService(true, mock(Environment.class)), mock(MeterRegistry.class), legacyTokenCompatibility);
    }
}
