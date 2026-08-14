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
 * 项目级实时事件流接口（§12.1，SSE）。
 * 返回 text/event-stream；可用 Last-Event-ID 断线续传，续传点过期返回 409。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 建立 SSE 连接，接收状态与产物事件（每 15 秒心跳，事件保留 24 小时）。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID projectId,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
            @AuthenticationPrincipal UUID userId) {
        return eventService.stream(projectId, userId, lastEventId);
    }
}
