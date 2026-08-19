package qg.qgent.sandboxworker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalServiceTokenFilterTest {
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void rejectsMissingTokenBeforeController() throws Exception {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setBackendServiceToken("worker-secret");
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(properties,
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("/internal/v1/tool-executions/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsConfiguredServiceToken() throws Exception {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setBackendServiceToken("worker-secret");
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(properties,
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("/internal/v1/tool-executions/1");
        request.addHeader("Authorization", "Bearer worker-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesPublicEndpointUntouched() throws Exception {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setBackendServiceToken("worker-secret");
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter(properties,
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath("");
        return request;
    }
}
