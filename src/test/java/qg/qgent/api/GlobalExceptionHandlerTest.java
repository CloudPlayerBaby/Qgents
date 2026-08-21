package qg.qgent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理单测：未映射路径/方法应返回 404/405（不被 Exception 兜底吞成 500）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        when(request.getAttribute(RequestIdFilter.ATTRIBUTE)).thenReturn("req-1");
    }

    @Test
    void notFoundReturns404EnvelopeInsteadOf500() {
        ResponseEntity<?> resp = handler.notFound(
                new NoResourceFoundException(HttpMethod.PUT, "/api/v1/projects/1/attachments/2",
                        "/api/v1/projects/1/attachments/2"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertThat(error.get("code")).isEqualTo("NOT_FOUND");
        assertThat(body.get("requestId")).isEqualTo("req-1");
    }

    @Test
    void methodNotSupportedReturns405Envelope() {
        ResponseEntity<?> resp = handler.methodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertThat(error.get("code")).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
