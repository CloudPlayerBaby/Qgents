package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.LoginRequest;
import qg.qgent.dto.PasswordResetRequest;
import qg.qgent.dto.RefreshTokenRequest;
import qg.qgent.dto.RegisterRequest;
import qg.qgent.dto.ResetPasswordRequest;
import qg.qgent.dto.UpdateMeRequest;
import qg.qgent.service.AuthService;

import java.util.Map;
import java.util.UUID;

/**
 * 认证与账户接口（§4）。
 * 注册/登录/刷新/重置密码无需已认证 Token；password 字段为平台 RSA 公钥加密的 Base64 密文（见契约 §4.1）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户并签发登录 Token。
     */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        return ok(authService.register(body), request);
    }

    /**
     * 邮箱密码登录并签发新 Token。
     */
    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ok(authService.login(body, fingerprint(request)), request);
    }

    /**
     * 用 refreshToken 轮换一组新 Token。
     */
    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        return ok(authService.refresh(body.getRefreshToken()), request);
    }

    /**
     * 注销当前登录态。
     */
    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@AuthenticationPrincipal UUID userId,
            @Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        authService.logout(userId, body.getRefreshToken());
        return ok(Map.of(), request);
    }

    /**
     * 发起找回密码邮件（未注册邮箱同样返回成功，规避枚举）。
     */
    @PostMapping("/auth/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> requestReset(@Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        authService.requestReset(body.getEmail(), fingerprint(request));
        return ok(Map.of("message", "如果邮箱已注册，重置邮件将很快发送"), request);
    }

    /**
     * 用重置令牌设置新密码并撤销全部 refreshToken。
     */
    @PostMapping("/auth/password-resets")
    public ApiResponse<?> reset(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        authService.reset(body);
        return ok(Map.of(), request);
    }

    /**
     * 获取当前账户、团队与项目角色摘要。
     */
    @GetMapping("/me")
    public ApiResponse<?> me(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(authService.me(userId), request);
    }

    /**
     * 修改昵称或头像。
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

    // 获取请求的指纹（IP 地址）
    private String fingerprint(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
