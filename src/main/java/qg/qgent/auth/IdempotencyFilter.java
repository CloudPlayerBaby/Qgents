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
 * 对项目范围的所有写请求强制要求 Idempotency-Key：
 * 首次 2xx 响应缓存到 idempotency_records；同键同请求体回放首次响应，
 * 同键不同请求体返回 409 IDEMPOTENCY_KEY_REUSED；缺键返回 400。
 * 事件流（GET）不受影响。
 * 在 SecurityConfig 中注册于 JwtAuthenticationFilter 之后，保证 SecurityContext 已就绪。
 */
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    private static final String UUID_PATTERN = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /**
     * 请求体缓存上限（字节）：超过上限的部分不参与幂等哈希，需保证不小于常见写请求体。
     */
    private static final int BODY_CACHE_LIMIT = 1024 * 1024;

    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))) {
            return true;
        }
        String path = request.getRequestURI();

        // GitHub Installations: POST, DELETE, POST /sync
        boolean isGitHubInstallApi = path.matches("^/api/v1/teams/[^/]+/integrations/github/installations(?:/[^/]+(?:/sync)?)?$");
        boolean isGitHubOAuthWriteApi = path.matches("^/api/v1/me/integrations/github/oauth(?:/start)?$");

        // 项目下的所有写接口都可能被移动端、SSE 刷新后的前端重试或网络层重放。
        // 不只保护仓库绑定和 Diff 审核，否则 Dry Run、CQ 决策、创建 MR 等高副作用操作会重复执行。
        boolean isProjectWriteApi = path.matches("^/api/v1/projects/[^/]+(?:/.*)?$");

        return !isGitHubInstallApi && !isGitHubOAuthWriteApi && !isProjectWriteApi;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 获取幂等键（Idempotency-Key）
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            writeError(request, response, 400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
            return;
        }

        // 获取当前操作用户的安全上下文
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = auth != null && auth.getPrincipal() instanceof UUID u ? u : null;
        if (userId == null) {
            // 未认证请求由安全链拦截；此处兜底放行，避免重复鉴权逻辑
            chain.doFilter(request, response);
            return;
        }

        // 计算用户指纹和请求范围标识
        byte[] fingerprint = fingerprint(userId);
        String scope = scope(request);

        // 尝试查询该用户在相同接口路径和相同幂等键下的缓存记录
        IdempotencyRecordEntity existing = idempotency.find(fingerprint, scope, key);
        if (existing != null) {
            // 如果存在缓存，需验证请求体哈希，防范同一幂等键被用于不同请求体的恶意/错误行为
            byte[] requestHash = hash(readBody(request));
            if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
                writeError(request, response, 409, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key 已被使用且请求体不同");
                return;
            }
            // 校验通过，直接将先前的响应内容回放并返回，不再执行后续过滤器链
            replay(response, existing);
            return;
        }

        // 包装请求和响应，使其后续可重复读取 Body 进行缓存
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, BODY_CACHE_LIMIT);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

        try {
            // 继续执行下游的过滤器和业务控制器逻辑
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            // 业务执行完毕（无论是成功还是抛错），记录请求体的哈希
            byte[] requestHash = hash(cachedRequest.getContentAsByteArray());
            int status = cachedResponse.getStatus();
            byte[] body = cachedResponse.getContentAsByteArray();

            // 仅当业务处理成功（状态码在 200~299 之间）时，进行响应的持久化缓存
            if (status >= 200 && status < 300) {
                try {
                    // 若无响应体（例如 204），保存一个空 Map，以避免 null 造成缓存被识别为待处理中
                    Map<String, Object> redacted = Map.of();
                    if (body.length > 0) {
                        redacted = mapper.readValue(body, new TypeReference<>() {
                        });
                    }
                    // 保存成功响应记录到数据库，供相同幂等键的下一次请求回放使用
                    idempotency.save(userId, fingerprint, scope, key, requestHash, status, redacted, null);
                } catch (Exception e) {
                    // 幂等记录写入失败不阻断当前正常的业务响应；重试可能重复创建，交由上层幂等约束兜底
                    log.warn("idempotency record save failed: {}", e.getMessage());
                }
            }
            // 必须将缓存中的真实响应拷贝写回到原始响应流中
            cachedResponse.copyBodyToResponse();
        }
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        // 与首次请求的哈希口径保持一致：只取前 BODY_CACHE_LIMIT 字节。
        // 若这里读全量，>1MB 的请求体（如代理上传大文件）重放时会因
        // 「全量哈希 vs 缓存前缀哈希」不一致被误判为 IDEMPOTENCY_KEY_REUSED。
        if (request instanceof ContentCachingRequestWrapper cached) {
            // 强制读取以填充缓存，保证下游可重复读
            try (InputStream in = cached.getInputStream()) {
                return in.readNBytes(BODY_CACHE_LIMIT);
            }
        }
        try (InputStream in = request.getInputStream()) {
            return in.readNBytes(BODY_CACHE_LIMIT);
        }
    }

    private void replay(HttpServletResponse response, IdempotencyRecordEntity record) throws IOException {
        response.setStatus(record.getResponseStatus());
        if (record.getResponseBodyRedacted() == null || record.getResponseBodyRedacted().isEmpty()) {
            return;
        }
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
        String query = request.getQueryString();
        return request.getMethod() + " " + path + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
