package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.AuthService;
import qg.qgent.auth.AuthCookieService;
import qg.qgent.api.ApiException;

import java.util.Map;
import java.util.UUID;

/**
 * 认证与账户接口
 * 注册/登录/刷新/重置密码无需已认证 Token；password 字段为平台 RSA 公钥加密的 Base64 密文（见契约 §4.1）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final AuthCookieService cookies;
    private final boolean legacyTokenCompatibility;

    public AuthController(AuthService authService, AuthCookieService cookies,
                          @org.springframework.beans.factory.annotation.Value("${app.auth.legacy-token-compatibility:true}") boolean legacyTokenCompatibility) {
        this.authService = authService;
        this.cookies = cookies;
        this.legacyTokenCompatibility = legacyTokenCompatibility;
    }

    /**
     * 契约 §4：注册新用户并签发登录 Token（需先通过邮箱验证码校验）。
     */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
                                   HttpServletResponse response,
                                   @RequestAttribute("_csrf") CsrfToken csrfToken) {
        return session(authService.register(body), request, response, csrfToken);
    }

    /**
     * 契约 §4 补充：发送注册邮箱验证码（6 位数字，10 分钟有效）。
     * 邮箱已注册返回 409；限流按 IP+邮箱计，防止验证码轰炸。
     */
    @PostMapping("/auth/register/verification-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> sendRegisterCode(@Valid @RequestBody SendVerificationCodeRequest body,
                                           HttpServletRequest request) {
        authService.sendRegisterCode(body.getEmail(), fingerprint(request));
        return ok(Map.of("message", "验证码已发送到邮箱，10 分钟内有效"), request);
    }

    /**
     * 契约 §4：邮箱密码登录并签发新 Token。
     */
    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                HttpServletResponse response,
                                @RequestAttribute("_csrf") CsrfToken csrfToken) {
        return session(authService.login(body, fingerprint(request)), request, response, csrfToken);
    }

    /**
     * 契约 §4：用 refreshToken 轮换一组新 Token。
     */
    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@RequestBody(required = false) RefreshTokenRequest body, HttpServletRequest request,
                                  HttpServletResponse response,
                                  @RequestAttribute("_csrf") CsrfToken csrfToken) {
        String raw = cookies.refreshToken(request);
        if (raw == null && legacyTokenCompatibility && body != null) raw = body.getRefreshToken();
        if (raw == null || raw.isBlank()) {
            cookies.clearSession(response);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "refresh token无效或已过期");
        }
        try {
            return session(authService.refresh(raw), request, response, csrfToken);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) cookies.clearSession(response);
            throw e;
        }
    }

    /**
     * 契约 §4：注销当前登录态。
     */
    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestBody(required = false) RefreshTokenRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        String raw = cookies.refreshToken(request);
        if (raw == null && legacyTokenCompatibility && body != null) raw = body.getRefreshToken();
        if (raw != null && !raw.isBlank()) authService.logout(raw);
        cookies.clearSession(response);
        return ok(Map.of(), request);
    }

    /** 为不同子域的浏览器前端签发并暴露 CSRF 请求头；认证 Cookie 仍不可由 JavaScript 读取。 */
    @GetMapping("/auth/csrf")
    public ResponseEntity<Void> csrf(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(csrfToken.getHeaderName(), csrfToken.getToken())
                .build();
    }

    /**
     * 契约 §4：发起找回密码邮件（未注册邮箱同样返回成功，规避枚举）。
     */
    @PostMapping("/auth/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> requestReset(@Valid @RequestBody PasswordResetRequest body,
                                       HttpServletRequest request) {
        authService.requestReset(body.getEmail(), fingerprint(request));
        return ok(Map.of("message", "如果邮箱已注册，重置邮件将很快发送"), request);
    }

    /**
     * 契约 §4：用重置令牌设置新密码并撤销全部 refreshToken。
     */
    @PostMapping("/auth/password-resets")
    public ApiResponse<?> reset(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        authService.reset(body);
        return ok(Map.of(), request);
    }

    /**
     * 契约 §4：获取当前账户、团队与项目角色摘要。
     */
    @GetMapping("/me")
    public ApiResponse<?> me(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(authService.me(userId), request);
    }

    /**
     * 契约 §4：修改昵称或头像。
     */
    @PatchMapping("/me")
    public ApiResponse<?> updateMe(@AuthenticationPrincipal UUID userId, @Valid @RequestBody UpdateMeRequest body,
                                   HttpServletRequest request) {
        return ok(authService.updateMe(userId, body), request);
    }

    // 生成统一的 API 响应
    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private ApiResponse<?> session(AuthTokensResponse tokens, HttpServletRequest request,
                                   HttpServletResponse response, CsrfToken csrfToken) {
        cookies.writeSession(response, tokens);
        response.setHeader(csrfToken.getHeaderName(), csrfToken.getToken());
        Object data = legacyTokenCompatibility ? tokens : new AuthSessionResponse(tokens.getUser());
        return ok(data, request);
    }

    // 获取请求的指纹（IP 地址）
    private String fingerprint(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
