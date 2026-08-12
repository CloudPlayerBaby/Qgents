package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.MessageResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.dto.PageSlice;
import qg.qgent.service.MessageService;

import java.util.UUID;

/**
 * 群消息接口（契约 §7 消息收发）。
 * <p>
 * 发送（POST）的 Idempotency-Key 由 {@code IdempotencyFilter} 统一强制与回放。
 */
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 发送文本、代码块、图片、文件或引用消息（需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, @Valid @RequestBody MessageSendRequest body, HttpServletRequest request) {
        return ok(messageService.send(userId, projectId, groupId, body), request);
    }

    /**
     * 游标拉取群消息，新消息在前；limit 默认 30、最大 100。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages")
    public PagedApiResponse<MessageResponse> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "30") int limit, HttpServletRequest request) {
        PageSlice<MessageResponse> slice = messageService.list(userId, projectId, groupId, cursor, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
