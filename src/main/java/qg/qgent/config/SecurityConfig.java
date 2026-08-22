package qg.qgent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import qg.qgent.auth.IdempotencyFilter;
import qg.qgent.auth.AuthCookieService;
import qg.qgent.auth.JwtAuthenticationFilter;
import qg.qgent.service.IdempotencyService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 安全配置
 * SecurityConfig
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper(com.fasterxml.jackson.databind.Module utcLocalDateTimeModule) {
        return new ObjectMapper().findAndRegisterModules().registerModule(utcLocalDateTimeModule);
    }

    /**
     * 加密密码
     *
     * @return BCryptPasswordEncoder
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 幂等过滤器 Bean：在 JwtAuthenticationFilter 之后注册，保证读取请求体前鉴权已就绪。
     */
    @Bean
    IdempotencyFilter idempotencyFilter(IdempotencyService idempotency, ObjectMapper mapper) {
        return new IdempotencyFilter(idempotency, mapper);
    }

    /**
     * 安全过滤链
     *
     * @param http
     * @param jwt
     * @param idempotency 写接口幂等过滤器，位于 JWT 鉴权之后
     * @param mapper
     * @return
     * @throws Exception
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt, IdempotencyFilter idempotency,
                                 ObjectMapper mapper, @Qualifier("cors") CorsConfigurationSource corsConfigurationSource,
                                 CookieCsrfTokenRepository csrfTokenRepository,
                                 AuthCookieService cookies,
                                 @Value("${app.auth.legacy-token-compatibility:true}") boolean legacyTokenCompatibility) throws Exception {
        return http
                // Cookie 认证必须校验 CSRF；兼容期仅允许旧 Bearer/旧认证端点绕过，关闭开关后全部写请求强制校验。
                .csrf(c -> c.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler())
                        .requireCsrfProtectionMatcher(csrfMatcher(legacyTokenCompatibility, cookies)))
                // 开启 CORS
                .cors(c -> c.configurationSource(corsConfigurationSource))
                // 不使用 Session
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求授权
                .authorizeHttpRequests(a -> a
                        // SSE 断连/超时后容器触发的 ASYNC/ERROR dispatch 会重走过滤链，此时已无 SecurityContext
                        // （且长连接可能已超出 access token 有效期）。派发是已通过初始鉴权的请求的收尾，
                        // 不构成新的授权入口，因此放行；初始 REQUEST 仍走完整鉴权。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register",
                                "/api/v1/auth/register/verification-codes", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/csrf",
                                "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/integrations/github/callback").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/integrations/github/oauth/callback").permitAll()
                        // GitHub Webhook 公开接口：安全依据为 X-Hub-Signature-256 验签，不携带 Qgents JWT。
                        .requestMatchers(HttpMethod.POST, "/api/v1/integrations/github/webhook").permitAll()
                        // Worker 内部调用使用独立 service token，由内部 Controller 自行校验。
                        .requestMatchers("/internal/v1/**").permitAll()
                        // Prometheus 在本地监控端口抓取指标，不携带业务 JWT；只暴露
                        // 监控所需的只读端点，业务接口仍必须登录。
                        .requestMatchers("/actuator/prometheus", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().anyRequest()
                        .authenticated())
                // 配置异常处理
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    // 401 原因记录为 debug：token 缺失/过期属客户端常态，避免刷屏；排查时开启 DEBUG 级别即可
                    log.debug("Unauthorized {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
                    res.setStatus(401);
                    res.setCharacterEncoding("UTF-8");
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(res.getWriter(),
                            Map.of("error", Map.of("code", "UNAUTHORIZED", "message", "需要登录", "details", List.of()),
                                    "requestId", String.valueOf(req.getAttribute("requestId"))));
                }))
                // 配置 JWT 过滤器
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                // 幂等过滤器紧跟 JWT 之后，仅处理已鉴权的 /api/v1/projects/** POST
                .addFilterAfter(idempotency, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(AuthCookieService cookies) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(AuthCookieService.CSRF_COOKIE);
        repository.setHeaderName(AuthCookieService.CSRF_HEADER);
        repository.setCookiePath(AuthCookieService.ACCESS_PATH);
        repository.setCookieCustomizer(builder -> builder
                .httpOnly(true)
                .secure(cookies.secure())
                .sameSite("Strict")
                .path(AuthCookieService.ACCESS_PATH));
        return repository;
    }

    private CsrfTokenRequestAttributeHandler csrfRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName("_csrf");
        return handler;
    }

    private RequestMatcher csrfMatcher(boolean legacyTokenCompatibility, AuthCookieService cookies) {
        return request -> {
            String method = request.getMethod();
            if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                    || HttpMethod.OPTIONS.matches(method) || HttpMethod.TRACE.matches(method)) {
                return false;
            }
            if (!legacyTokenCompatibility) return true;
            boolean cookieAuthenticated = cookies.accessToken(request) != null || cookies.refreshToken(request) != null;
            String authorization = request.getHeader("Authorization");
            if (!cookieAuthenticated && authorization != null && authorization.startsWith("Bearer ")) return false;
            String path = request.getRequestURI();
            boolean legacyBootstrap = "/api/v1/auth/login".equals(path)
                    || "/api/v1/auth/register".equals(path)
                    || "/api/v1/auth/refresh".equals(path);
            return cookieAuthenticated || !legacyBootstrap
                    || request.getHeader(AuthCookieService.CSRF_HEADER) != null;
        };
    }

    /**
     * 跨域配置
     *
     * @param origins
     * @return
     */
    @Bean
    CorsConfigurationSource cors(@Value("${app.cors-allowed-origins}") String origins) {
        // 配置跨域
        CorsConfiguration c = new CorsConfiguration();
        // 允许的来源
        List<String> allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException("Cookie 认证不允许 CORS_ALLOWED_ORIGINS 使用通配符");
        }
        c.setAllowedOrigins(allowedOrigins);
        // 允许的请求方法
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 允许的请求头：Idempotency-Key（写接口幂等）、Last-Event-ID（SSE 续传）
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key",
                "Last-Event-ID", AuthCookieService.CSRF_HEADER));
        c.setExposedHeaders(List.of(AuthCookieService.CSRF_HEADER));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 注册跨域配置
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
