package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.service.NotificationService;

import java.util.Map;
import java.util.UUID;

/**
 * 通知中心接口
 * 按用户维度持久化已读状态与历史列表；不经过 IdempotencyFilter，无需 Idempotency-Key。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 契约 §7.1：返回当前用户通知列表（含 isRead，按时间倒序）。
     */
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId,
                               HttpServletRequest request) {
        return ok(notificationService.list(userId), request);
    }

    /**
     * 契约 §7.1：标记单条通知已读（幂等）。
     */
    @PostMapping("/{notificationId}/read")
    public ApiResponse<?> markRead(@AuthenticationPrincipal UUID userId,
                                   @PathVariable UUID notificationId, HttpServletRequest request) {
        notificationService.markRead(userId, notificationId);
        return ok(Map.of(), request);
    }

    /**
     * 契约 §7.1：全部通知已读（幂等）。
     */
    @PostMapping("/read-all")
    public ApiResponse<?> markAllRead(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        notificationService.markAllRead(userId);
        return ok(Map.of(), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
