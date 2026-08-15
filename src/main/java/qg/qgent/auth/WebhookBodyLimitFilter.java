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
 * <p>
 * 超限判定采用「读取 limit+1 字节后才确认」：恰好等于上限的请求能正常读完（EOF），
 * 只有确实存在第 limit+1 个字节时才判定超限，并以专用异常由本过滤器直接返回 413，
 * 避免 IOException 被 MVC 包装成 400。
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
            writeTooLarge(response);
            return;
        }
        try {
            chain.doFilter(new LimitedBodyRequestWrapper(request, properties.getMaxBodyBytes()), response);
        } catch (WebhookBodyTooLargeException e) {
            writeTooLarge(response);
        }
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(413);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"WEBHOOK_BODY_TOO_LARGE\","
                + "\"message\":\"Webhook 请求体超过大小上限\",\"details\":[]},\"requestId\":null}");
    }

    /**
     * 包装请求：输入流读取超过上限时抛出专用异常，由过滤器统一转为 413。
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

    /**
     * 受限输入流：允许读取 limit+1 字节以区分「恰好等于上限」与「超限」；
     * 确认超限后抛出 {@link WebhookBodyTooLargeException}。
     */
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
            int value = delegate.read();
            if (value < 0) {
                finished = true;
                return -1;
            }
            readBytes++;
            if (readBytes > limit) {
                throw new WebhookBodyTooLargeException();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read < 0) {
                finished = true;
                return -1;
            }
            readBytes += read;
            if (readBytes > limit) {
                throw new WebhookBodyTooLargeException();
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

    /**
     * 请求体超限专用异常：由 WebhookBodyLimitFilter 捕获并转为 413。
     */
    static final class WebhookBodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
