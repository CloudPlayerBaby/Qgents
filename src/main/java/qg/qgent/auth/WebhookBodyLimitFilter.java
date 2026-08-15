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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * GitHub Webhook 请求体上限过滤器。
 * 在请求进入 Controller 之前限制请求体大小，只作用于 POST /api/v1/integrations/github/webhook。
 * <p>
 * 实现要点：由本过滤器主动读取请求体（最多 limit+1 字节）并判定大小——
 * 恰好等于上限的请求完整读入并包装为可重复读的请求传给下游；确认超限（存在第 limit+1 个字节）
 * 时直接返回 413。这样 413 判定完全发生在 DispatcherServlet 之前，不依赖流内抛异常，
 * 避免 Spring MVC 把读取异常包装成 HttpMessageNotReadableException 后返回 400。
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
        // Content-Length 声明超限：不读 body 直接 413
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > properties.getMaxBodyBytes()) {
            writeTooLarge(response);
            return;
        }
        // 主动读取 limit+1 字节：多出的 1 字节用于区分「恰好等于上限」与「超限」
        byte[] limited = readLimited(request.getInputStream(), properties.getMaxBodyBytes() + 1L);
        if (limited.length > properties.getMaxBodyBytes()) {
            writeTooLarge(response);
            return;
        }
        chain.doFilter(new CachedBodyRequestWrapper(request, limited), response);
    }

    /**
     * 读取最多 maxBytes 字节；底层流提前 EOF 则返回已读内容。
     */
    private byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while (total < maxBytes && (read = input.read(chunk, 0,
                (int) Math.min(chunk.length, maxBytes - total))) != -1) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toByteArray();
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"WEBHOOK_BODY_TOO_LARGE\","
                + "\"message\":\"Webhook 请求体超过大小上限\",\"details\":[]},\"requestId\":null}");
    }

    /**
     * 把已缓存的请求体包装为可重复读的请求，下游（Controller @RequestBody byte[]）直接消费缓存。
     */
    private static final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        private CachedBodyRequestWrapper(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final InputStream delegate = new ByteArrayInputStream(cachedBody);

                @Override
                public int read() throws IOException {
                    return delegate.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    return delegate.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    try {
                        return delegate.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    throw new UnsupportedOperationException("blocking read only");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(new ByteArrayInputStream(cachedBody),
                            java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }
    }
}
