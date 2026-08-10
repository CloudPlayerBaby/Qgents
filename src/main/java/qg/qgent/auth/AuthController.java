package qg.qgent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<?> register(@Valid @RequestBody AuthDtos.Register body, HttpServletRequest req) {
        return ok(auth.register(body), req);
    }

    @PostMapping("/auth/login")
    ApiResponse<?> login(@Valid @RequestBody AuthDtos.Login body, HttpServletRequest req) {
        return ok(auth.login(body, fingerprint(req)), req);
    }

    @PostMapping("/auth/refresh")
    ApiResponse<?> refresh(@Valid @RequestBody AuthDtos.Refresh body, HttpServletRequest req) {
        return ok(auth.refresh(body.refreshToken()), req);
    }

    @PostMapping("/auth/logout")
    ApiResponse<?> logout(@AuthenticationPrincipal UUID user, @Valid @RequestBody AuthDtos.Refresh body,
            HttpServletRequest req) {
        auth.logout(user, body.refreshToken());
        return ok(Map.of(), req);
    }

    @PostMapping("/auth/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<?> resetRequest(@Valid @RequestBody AuthDtos.ResetRequest body, HttpServletRequest req) {
        auth.requestReset(body.email(), fingerprint(req));
        return ok(Map.of("message", "如果邮箱已注册，重置邮件将很快发送"), req);
    }

    @PostMapping("/auth/password-resets")
    ApiResponse<?> reset(@Valid @RequestBody AuthDtos.Reset body, HttpServletRequest req) {
        auth.reset(body);
        return ok(Map.of(), req);
    }

    @GetMapping("/me")
    ApiResponse<?> me(@AuthenticationPrincipal UUID user, HttpServletRequest req) {
        return ok(auth.me(user), req);
    }

    @PatchMapping("/me")
    ApiResponse<?> update(@AuthenticationPrincipal UUID user, @Valid @RequestBody AuthDtos.UpdateMe body,
            HttpServletRequest req) {
        return ok(auth.updateMe(user, body), req);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest req) {
        return ApiResponse.ok(data, (String) req.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private String fingerprint(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
