package qg.qgent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import qg.qgent.config.GitHubWebhookProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebhookBodyLimitFilterTest {
    private final GitHubWebhookProperties properties = new GitHubWebhookProperties();
    private WebhookBodyLimitFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        properties.setSecret("secret");
        properties.setMaxBodyBytes(10);
        filter = new WebhookBodyLimitFilter(properties);
        response = new MockHttpServletResponse();
    }

    /**
     * 下游 FilterChain：完整读取请求体并校验内容，模拟 Spring MVC 消费 body。
     */
    private static final class BodyReadingFilter implements FilterChain {
        private byte[] consumed;

        @Override
        public void doFilter(ServletRequest request, ServletResponse servletResponse) throws IOException {
            consumed = request.getInputStream().readAllBytes();
        }

        byte[] consumed() {
            return consumed;
        }
    }

    private MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/integrations/github/webhook");
        request.setContent(body);
        return request;
    }

    /**
     * 模拟真正的 chunked 请求：getContentLengthLong() 返回 -1（无 Content-Length），
     * 但 getInputStream() 仍能读到 body，用于覆盖 readLimited() 超限分支。
     */
    private MockHttpServletRequest chunkedRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/integrations/github/webhook") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContent(body);
        return request;
    }

    @Test
    void contentLengthOverLimitReturns413() throws Exception {
        byte[] body = "12345678901".getBytes(StandardCharsets.UTF_8); // 11 > 10
        filter.doFilter(request(body), response, new BodyReadingFilter());
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("WEBHOOK_BODY_TOO_LARGE"));
    }

    @Test
    void chunkedBodyExactlyAtLimitPassesThrough() throws Exception {
        // 无 Content-Length 的 chunked 请求：恰好 10 字节，应完整读到并放行
        byte[] body = "1234567890".getBytes(StandardCharsets.UTF_8); // 10 == limit
        BodyReadingFilter downstream = new BodyReadingFilter();
        filter.doFilter(chunkedRequest(body), response, downstream);
        assertEquals(200, response.getStatus());
        assertArrayEquals(body, downstream.consumed());
    }

    @Test
    void chunkedBodyOverLimitReturns413() throws Exception {
        // 无 Content-Length 的 chunked 请求：11 字节，filter 走 readLimited() 判定超限，返回 413
        byte[] body = "12345678901".getBytes(StandardCharsets.UTF_8);
        BodyReadingFilter downstream = new BodyReadingFilter();
        filter.doFilter(chunkedRequest(body), response, downstream);
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("WEBHOOK_BODY_TOO_LARGE"));
    }

    @Test
    void nonWebhookPathIsNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/x/tasks");
        request.setContent("12345678901234567890".getBytes(StandardCharsets.UTF_8));
        BodyReadingFilter downstream = new BodyReadingFilter();
        filter.doFilter(request, response, downstream);
        assertEquals(200, response.getStatus());
        assertNotNull(downstream.consumed());
    }
}
