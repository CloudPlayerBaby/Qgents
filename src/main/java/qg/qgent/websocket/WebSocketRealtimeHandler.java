package qg.qgent.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import qg.qgent.service.RealtimeHub;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/**
 * 实时 WebSocket 处理器（单连接 + 用户级聚合流，契约 §12.1 补充）。
 * <p>
 * 连接建立时从握手会话属性读取已鉴权 {@code userId}，登记到 {@link RealtimeHub}；此后服务端把该用户
 * 可见项目/团队事件与本人通知聚合推送到此单条连接。客户端上行数据一般不是必需（实时推送单向），
 * 预留 handleTextMessage 供心跳/查询类扩展。
 * <p>
 * 心跳沿用 SSE 的 15 秒约定：定时向每条在线连接发送 WebSocket PingMessage 保活；连接断开或发送失败
 * 时主动关闭并注销，避免失效连接残留。
 */
@Component
public class WebSocketRealtimeHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebSocketRealtimeHandler.class);

    /**
     * 心跳间隔（毫秒），对齐 SSE 每 15 秒心跳。
     */
    private static final long HEARTBEAT_MS = 15_000L;

    private final RealtimeHub hub;

    public WebSocketRealtimeHandler(RealtimeHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object raw = session.getAttributes().get(RealtimeAuthInterceptor.USER_ID_ATTR);
        if (!(raw instanceof UUID userId)) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        hub.register(userId, session);
        // 鉴权成功确认帧：客户端据此判定连接可用
        session.sendMessage(new TextMessage(
                "{\"type\":\"hello\",\"userId\":\"" + userId + "\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = sessionUserId(session);
        if (userId != null) {
            hub.unregister(userId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        UUID userId = sessionUserId(session);
        if (userId != null) {
            hub.unregister(userId, session);
        }
        log.debug("realtime transport error, userId={}: {}", userId, exception.getMessage());
    }

    /**
     * 预留的客户端上行入口：当前实时通道为服务端单向推送，一般忽略客户端文本；后续如需
     * 心跳应答、按需订阅或查询扩展可在此解析处理。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 预留；当前协议不定义客户端上行载荷，忽略（记 debug 便于排查异常上行）
        log.debug("realtime unsolicited client frame ignored, session={}", session.getId());
    }

    /**
     * 定时心跳：对每条在线连接发送 PingMessage 保活；发送失败则关闭并注销该连接。
     * 依赖 Qgents 全局 {@code @EnableScheduling} 生效。
     */
    @Scheduled(fixedRate = HEARTBEAT_MS)
    public void heartbeat() {
        hub.forEachSession(session -> {
            try {
                session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[0])));
            } catch (IOException | IllegalStateException e) {
                UUID userId = sessionUserId(session);
                if (userId != null) {
                    hub.unregister(userId, session);
                }
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 连接已失效时 close 本身也可能抛错，忽略
                }
            }
        });
    }

    private UUID sessionUserId(WebSocketSession session) {
        Object raw = session.getAttributes().get(RealtimeAuthInterceptor.USER_ID_ATTR);
        return raw instanceof UUID userId ? userId : null;
    }
}
