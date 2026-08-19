package qg.qgent.sandboxworker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 保护 Worker 的全部内部接口。
 *
 * <p>Worker 没有用户 JWT，主后端通过独立的服务令牌调用。令牌缺失或配置为空时
 * fail-closed，避免把工具执行、完整 stdout/stderr 和 Git 操作暴露给公网。</p>
 */
@Component
public class InternalServiceTokenFilter extends OncePerRequestFilter {
    private final SandboxWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public InternalServiceTokenFilter(SandboxWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(request.getContextPath() + "/internal/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String configured = properties.getBackendServiceToken();
        String authorization = request.getHeader("Authorization");
        if (!valid(configured, authorization)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", configured == null || configured.isBlank()
                            ? "INTERNAL_AUTH_NOT_CONFIGURED" : "INTERNAL_AUTH_REQUIRED",
                    "message", "需要有效的内部服务令牌"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean valid(String configured, String authorization) {
        if (configured == null || configured.isBlank()
                || authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
