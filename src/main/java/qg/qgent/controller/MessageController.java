package qg.qgent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.Page;
import qg.qgent.api.PagedResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.MessageListResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.service.IdempotencyService;
import qg.qgent.service.MessageService;

import java.util.UUID;

/**
 * 群消息接口（契约 §7 消息收发）。
 */
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messageService;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public MessageController(MessageService messageService, IdempotencyService idempotency, ObjectMapper mapper) {
        this.messageService = messageService;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /**
     * 发送文本、代码块、图片、文件或引用消息（写操作，需 Idempotency-Key）。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MessageSendRequest body, HttpServletRequest request) {
        JsonNode result = idempotency.run("POST:/api/v1/projects/{projectId}/groups/{groupId}/messages",
                idempotencyKey, userId, body, HttpStatus.CREATED.value(),
                () -> mapper.valueToTree(ok(messageService.send(userId, projectId, groupId, body), request)));
        return fromJson(result);
    }

    /**
     * 游标拉取群消息，新消息在前；limit 默认 30、最大 100。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages")
    public PagedResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "30") int limit, HttpServletRequest request) {
        MessageListResponse page = messageService.list(userId, projectId, groupId, cursor, limit);
        return PagedResponse.of(page.getMessages(), new Page(page.getNextCursor(), page.isHasMore()),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private ApiResponse<?> fromJson(JsonNode node) {
        try {
            return mapper.treeToValue(node, ApiResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应解析失败", e);
        }
    }
}
