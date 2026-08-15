package qg.qgent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qg.qgent.api.RequestIdFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final ObjectMapper mapper;

    public JwtAuthenticationFilter(TokenService tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 内部服务接口使用 SANDBOX_BACKEND_SERVICE_TOKEN，不是用户 JWT。
        String requestUri = request.getRequestURI();
        return requestUri != null && requestUri.startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // 尝试获取响应头
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            var userId = tokens.verifyAccess(header.substring(7));
            if (userId == null) {
                unauthorized(req, res);
                return;
            }
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }
        chain.doFilter(req, res);
    }

    // 处理未授权的请求，返回401错误
    private void unauthorized(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setStatus(401);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getWriter(),
                Map.of("error",
                        Map.of("code", "INVALID_ACCESS_TOKEN", "message", "access token无效或已过期", "details", List.of()),
                        "requestId", String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }
}
