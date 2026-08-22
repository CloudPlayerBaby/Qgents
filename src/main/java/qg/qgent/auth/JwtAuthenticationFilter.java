package qg.qgent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import qg.qgent.api.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTH_SOURCE_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".source";
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final AuthCookieService cookies;
    private final boolean legacyTokenCompatibility;
    private final Counter cookieAuthenticationCounter;
    private final Counter bearerAuthenticationCounter;

    public JwtAuthenticationFilter(TokenService tokens, ObjectMapper mapper, AuthCookieService cookies,
                                   MeterRegistry meterRegistry,
                                   @org.springframework.beans.factory.annotation.Value("${app.auth.legacy-token-compatibility:true}") boolean legacyTokenCompatibility) {
        this.tokens = tokens;
        this.mapper = mapper;
        this.cookies = cookies;
        this.legacyTokenCompatibility = legacyTokenCompatibility;
        this.cookieAuthenticationCounter = Counter.builder("qgents.authentication.requests")
                .tag("source", "cookie").register(meterRegistry);
        this.bearerAuthenticationCounter = Counter.builder("qgents.authentication.requests")
                .tag("source", "bearer").register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 内部服务接口使用 SANDBOX_BACKEND_SERVICE_TOKEN，不是用户 JWT。
        String requestUri = request.getRequestURI();
        return requestUri != null && requestUri.startsWith("/internal/v1/");
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // ASYNC dispatch 是已通过初始鉴权的请求（如 SSE 流）断连/超时后的收尾派发；
        // 此时长连接可能已超出 access token 有效期，重复解析 JWT 会对已提交响应写 401。
        // 派发类型已在 SecurityConfig 中按 DispatcherType.ASYNC 放行。
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cookieToken = cookies.accessToken(req);
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        String token = cookieToken != null ? cookieToken : legacyTokenCompatibility ? bearerToken(header) : null;
        if (token != null) {
            var userId = tokens.verifyAccess(token);
            if (userId == null) {
                if (isPublicAuthRoute(req)) {
                    chain.doFilter(req, res);
                    return;
                }
                unauthorized(req, res);
                return;
            }
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            if (cookieToken != null) {
                req.setAttribute(AUTH_SOURCE_ATTRIBUTE, "cookie");
                cookieAuthenticationCounter.increment();
            } else {
                req.setAttribute(AUTH_SOURCE_ATTRIBUTE, "bearer");
                bearerAuthenticationCounter.increment();
            }
        }
        chain.doFilter(req, res);
    }

    private String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private boolean isPublicAuthRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/v1/auth/");
    }

    // 处理未授权的请求，返回401错误
    private void unauthorized(HttpServletRequest req, HttpServletResponse res) throws IOException {
        // 响应已提交（如 SSE 流式响应断连派发）时无法再写错误体，避免二次异常
        if (res.isCommitted()) {
            return;
        }
        res.setStatus(401);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getWriter(),
                Map.of("error",
                        Map.of("code", "INVALID_ACCESS_TOKEN", "message", "access token无效或已过期", "details", List.of()),
                        "requestId", String.valueOf(req.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }
}
