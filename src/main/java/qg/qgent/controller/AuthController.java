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
 * 认证与账户端点（4）。
 * 注册、登录、Token 刷新、登出与密码重置，以及当前用户信息查询/修改；
 * 除登出与 /me 外均无需已认证 Token。注册、登录、密码重置中的 password 必须是
 * 平台 RSA 公钥加密后的 Base64 密文（见契约 4.1）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册新用户并直接签发 accessToken / refreshToken；密码须为平台 RSA 公钥加密后的 Base64 密文，邮箱重复返回 409。
     */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        return ok(authService.register(body), request);
    }

    /**
     * 校验邮箱与 RSA 加密密码后签发新的 accessToken / refreshToken；连续失败会触发限流（429）。
     */
    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ok(authService.login(body, fingerprint(request)), request);
    }

    /**
     * 用未过期的 refreshToken 换取一组新 Token，旧 refreshToken 随即失效。
     */
    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        return ok(authService.refresh(body.getRefreshToken()), request);
    }

    /**
     * 注销当前登录态，使请求中的 refreshToken 失效。
     */
    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@AuthenticationPrincipal UUID userId,
            @Valid @RequestBody RefreshTokenRequest body, HttpServletRequest request) {
        authService.logout(userId, body.getRefreshToken());
        return ok(Map.of(), request);
    }

    /**
     * 请求密码重置；邮箱已注册时发送重置邮件，未注册时同样返回成功以规避邮箱枚举。
     */
    @PostMapping("/auth/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> requestReset(@Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        authService.requestReset(body.getEmail(), fingerprint(request));
        return ok(Map.of("message", "如果邮箱已注册，重置邮件将很快发送"), request);
    }

    /**
     * 使用重置令牌与新密码（RSA 密文）重置密码，并撤销该用户全部 refreshToken。
     */
    @PostMapping("/auth/password-resets")
    public ApiResponse<?> reset(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        authService.reset(body);
        return ok(Map.of(), request);
    }

    /**
     * 获取当前用户的资料、所属团队与可见项目。
     */
    @GetMapping("/me")
    public ApiResponse<?> me(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(authService.me(userId), request);
    }

    /**
     * 更新当前用户昵称或头像地址，至少提供一个修改字段。
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
