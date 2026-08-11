package qg.qgent.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.service.EventService;

import java.util.UUID;

/**
 * 项目级实时事件流端点（12.1）。
 * 返回 text/event-stream，不套用 JSON 成功响应；客户端可用 Last-Event-ID 断线续传，
 * 续传点超出 24 小时保留窗口时返回 409 EVENT_CURSOR_EXPIRED。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 建立项目级 SSE 连接，接收状态与产物事件。
     * 仅项目成员可订阅；每 15 秒服务端发送心跳，事件至少保留 24 小时。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID projectId,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
            @AuthenticationPrincipal UUID userId) {
        return eventService.stream(projectId, userId, lastEventId);
    }
}
