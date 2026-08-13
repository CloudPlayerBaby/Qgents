package qg.qgent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import qg.qgent.auth.IdempotencyFilter;
import qg.qgent.auth.JwtAuthenticationFilter;
import qg.qgent.service.IdempotencyService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 安全配置
 * SecurityConfig
 */
@Configuration
public class SecurityConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
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
            ObjectMapper mapper, @Qualifier("cors") CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                // 不启用 CSRF
                .csrf(c -> c.disable())
                // 开启 CORS
                .cors(c -> c.configurationSource(corsConfigurationSource))
                // 不使用 Session
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求授权
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/integrations/github/callback").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().anyRequest()
                        .authenticated())
                // 配置异常处理
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
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
        c.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        // 允许的请求方法
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        // 允许的请求头：Idempotency-Key（写接口幂等）、Last-Event-ID（SSE 续传）
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key",
                "Last-Event-ID"));
        // 允许携带 Cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 注册跨域配置
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
