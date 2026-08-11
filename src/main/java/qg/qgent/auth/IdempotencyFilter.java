package qg.qgent.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.entity.IdempotencyRecordEntity;
import qg.qgent.service.IdempotencyService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 写接口幂等过滤器。
 * 对 /api/v1/projects/** 的 POST 请求强制要求 Idempotency-Key：
 * 首次 2xx 响应缓存到 idempotency_records；同键同请求体回放首次响应，
 * 同键不同请求体返回 409 IDEMPOTENCY_KEY_REUSED；缺键返回 400。
 * 事件流（GET）不受影响。
 * 在 SecurityConfig 中注册于 JwtAuthenticationFilter 之后，保证 SecurityContext 已就绪。
 */
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    private static final String UUID_PATTERN = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** 请求体缓存上限（字节）：超过上限的部分不参与幂等哈希，需保证不小于常见写请求体。 */
    private static final int BODY_CACHE_LIMIT = 1024 * 1024;

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/projects/")) {
            return true;
        }
        // ProjectController 已使用事务型 IdempotencyService，避免过滤器再次创建同键记录。
        String path = request.getRequestURI();
        return path.matches("^/api/v1/projects/[^/]+/(archive|restore|members)$");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            writeError(request, response, 400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = auth != null && auth.getPrincipal() instanceof UUID u ? u : null;
        if (userId == null) {
            // 未认证请求由安全链拦截；此处兜底放行，避免重复鉴权逻辑
            chain.doFilter(request, response);
            return;
        }

        byte[] fingerprint = fingerprint(userId);
        String scope = scope(request);

        IdempotencyRecordEntity existing = idempotency.find(fingerprint, scope, key);
        if (existing != null) {
            byte[] requestHash = hash(readBody(request));
            if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
                writeError(request, response, 409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key 已被使用且请求体不同");
                return;
            }
            replay(response, existing);
            return;
        }

        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, BODY_CACHE_LIMIT);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        byte[] requestHash = hash(readBody(cachedRequest));
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            int status = cachedResponse.getStatus();
            byte[] body = cachedResponse.getContentAsByteArray();
            if (status >= 200 && status < 300 && body.length > 0) {
                try {
                    Map<String, Object> redacted = mapper.readValue(body, new TypeReference<>() {
                    });
                    idempotency.save(userId, fingerprint, scope, key, requestHash, status, redacted, null);
                } catch (Exception e) {
                    // 幂等记录写入失败不阻断响应；重试可能重复创建，交由上层幂等约束兜底
                    log.warn("idempotency record save failed: {}", e.getMessage());
                }
            }
            cachedResponse.copyBodyToResponse();
        }
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request instanceof ContentCachingRequestWrapper cached) {
            // 强制读取以填充缓存，保证下游可重复读
            try (InputStream in = cached.getInputStream()) {
                return in.readAllBytes();
            }
        }
        try (InputStream in = request.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyRecordEntity record) throws IOException {
        response.setStatus(record.getResponseStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), record.getResponseBodyRedacted());
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(),
                Map.of("error", Map.of("code", code, "message", message, "details", List.of()),
                        "requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }

    private byte[] fingerprint(UUID userId) {
        return hash(userId.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hash(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String scope(HttpServletRequest request) {
        String path = request.getRequestURI().replaceAll(UUID_PATTERN, "{id}");
        return request.getMethod() + " " + path;
    }
}
