package qg.qgent.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.web.util.WebUtils;
import qg.qgent.dto.AuthTokensResponse;

import java.time.Duration;

/**
 * 浏览器认证 Cookie 的唯一读写入口。
 *
 * <p>认证 Cookie 必须保持 host-only，不能设置 Domain；access 与 refresh 使用不同 Path，
 * 防止 refresh token 被发送到不需要它的业务接口。</p>
 */
@Component
public class AuthCookieService {
    public static final String ACCESS_COOKIE = "qgents_access_token";
    public static final String REFRESH_COOKIE = "qgents_refresh_token";
    public static final String CSRF_COOKIE = "qgents_csrf_token";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    public static final String ACCESS_PATH = "/api/v1";
    public static final String REFRESH_PATH = "/api/v1/auth";

    private final boolean secure;

    public AuthCookieService(@org.springframework.beans.factory.annotation.Value("${app.auth.cookie-secure:true}") boolean secure,
                             Environment environment) {
        if (!secure && java.util.Arrays.stream(environment.getActiveProfiles()).noneMatch("local"::equals)) {
            throw new IllegalStateException("AUTH_COOKIE_SECURE=false 仅允许 local profile 使用");
        }
        this.secure = secure;
    }

    public void writeSession(HttpServletResponse response, AuthTokensResponse tokens) {
        add(response, ACCESS_COOKIE, tokens.getAccessToken(), ACCESS_PATH,
                Duration.ofSeconds(tokens.getAccessTokenExpiresIn()));
        add(response, REFRESH_COOKIE, tokens.getRefreshToken(), REFRESH_PATH,
                Duration.ofSeconds(tokens.getRefreshTokenExpiresIn()));
    }

    public void clearSession(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", ACCESS_PATH, Duration.ZERO);
        add(response, REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
        add(response, CSRF_COOKIE, "", ACCESS_PATH, Duration.ZERO);
    }

    public String accessToken(HttpServletRequest request) {
        return value(request, ACCESS_COOKIE);
    }

    public String refreshToken(HttpServletRequest request) {
        return value(request, REFRESH_COOKIE);
    }

    public boolean secure() {
        return secure;
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String value(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null || cookie.getValue() == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }
}
