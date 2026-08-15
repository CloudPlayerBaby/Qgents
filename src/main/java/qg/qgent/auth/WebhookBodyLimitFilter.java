package qg.qgent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qg.qgent.config.GitHubWebhookProperties;

import java.io.IOException;
import java.io.InputStream;

/**
 * GitHub Webhook 请求体上限过滤器。
 * 在请求进入 Controller 之前限制请求体大小：Content-Length 超限直接返回 413，
 * 并以受限输入流包装请求，防止超大 chunked body 被完整读入内存。
 * 只作用于 POST /api/v1/integrations/github/webhook；其他请求直接放行。
 */
@Component
public class WebhookBodyLimitFilter extends OncePerRequestFilter {
    private final GitHubWebhookProperties properties;

    public WebhookBodyLimitFilter(GitHubWebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/integrations/github/webhook".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > properties.getMaxBodyBytes()) {
            response.setStatus(413);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"WEBHOOK_BODY_TOO_LARGE\","
                    + "\"message\":\"Webhook 请求体超过大小上限\",\"details\":[]},\"requestId\":null}");
            return;
        }
        chain.doFilter(new LimitedBodyRequestWrapper(request, properties.getMaxBodyBytes()), response);
    }

    /**
     * 包装请求：输入流读取超过上限时抛出 IOException，避免超大 body 进入内存。
     */
    private static final class LimitedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final int limit;

        private LimitedBodyRequestWrapper(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(super.getInputStream(), limit);
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private final int limit;
        private int readBytes;
        private boolean finished;

        private LimitedServletInputStream(InputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (readBytes >= limit) {
                throw new IOException("Webhook body exceeds size limit");
            }
            int value = delegate.read();
            if (value >= 0) {
                readBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (readBytes >= limit) {
                throw new IOException("Webhook body exceeds size limit");
            }
            int allowed = Math.min(length, limit - readBytes);
            int read = delegate.read(buffer, offset, allowed);
            if (read > 0) {
                readBytes += read;
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            throw new UnsupportedOperationException("blocking read only");
        }
    }
}
