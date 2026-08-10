package qg.qgent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
import qg.qgent.auth.JwtAuthenticationFilter;

import java.util.List;
import java.util.Map;

/**
 * 安全配置
 * SecurityConfig
 */
@Configuration
public class SecurityConfig {

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
     * 安全过滤链
     * 
     * @param http
     * @param jwt
     * @param mapper
     * @return
     * @throws Exception
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt, ObjectMapper mapper) throws Exception {
        return http
                // 不启用 CSRF
                .csrf(c -> c.disable())
                // 开启 CORS
                .cors(c -> {
                })
                // 不使用 Session
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求授权
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets")
                        .permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().anyRequest()
                        .authenticated())
                // 配置异常处理
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(res.getWriter(),
                            Map.of("error", Map.of("code", "UNAUTHORIZED", "message", "需要登录", "details", List.of()),
                                    "requestId", String.valueOf(req.getAttribute("requestId"))));
                }))
                // 配置 JWT 过滤器
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
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
        c.setAllowedOrigins(List.of(origins.split(",")));
        // 允许的请求方法
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        // 允许的请求头
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        // 允许携带 Cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 注册跨域配置
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
