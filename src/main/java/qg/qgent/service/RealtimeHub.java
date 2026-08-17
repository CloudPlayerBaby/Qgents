package qg.qgent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import qg.qgent.websocket.RealtimeFrame;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WebSocket 实时连接注册表（单连接 + 用户级聚合流，契约 §12.1 补充）。
 * <p>
 * 以 {@code userId → 在线连接集合} 维护每个用户的全部 WebSocket 会话（支持同一账号多设备/多标签
 * 同时在线，都收到聚合推送）。事件发布方（{@link EventService}）通过
 * {@link #broadcastToUsers} 将脱敏事件以 JSON 文本帧推送给目标用户的全部在线连接。
 * <p>
 * 本注册表仅承载「在线实时推送」；事件不在此落库续传（断线后由前端以 REST 查询兜底）。
 * 注册表内会话集合使用并发容器，并容忍发送失败时主动关闭并清理失效连接，避免泄漏。
 */
@Service
public class RealtimeHub {
    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public RealtimeHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 注册一个用户的新在线连接（多设备/多标签各自独立 session）。
     *
     * @param userId  已鉴权用户 ID
     * @param session 已建立的 WebSocket 会话
     */
    public void register(UUID userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /**
     * 注销一个连接（连接关闭时由 Handler 调用）。
     *
     * @param userId  用户 ID
     * @param session 关闭的 WebSocket 会话
     */
    public void unregister(UUID userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId, sessions);
            }
        }
    }

    /**
     * 向一组用户的全部在线连接推送一条实时帧。
     * <p>
     * 逐个 session 发送；发送失败/连接失效的连接被关闭并清理。串行化帧若失败仅记错误，
     * 不向调用方（业务事务）抛出，保证实时推送失败不影响业务写入。
     *
     * @param userIds 目标用户 ID 集合
     * @param frame   待推送的脱敏实时帧
     */
    public void broadcastToUsers(Set<UUID> userIds, RealtimeFrame frame) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn("realtime frame serialization failed, type={}: {}", frame.type(), e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(json);
        for (UUID userId : userIds) {
            Set<WebSocketSession> sessions = sessionsByUser.get(userId);
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException | IllegalStateException e) {
                    log.debug("realtime send failed, userId={}: {}", userId, e.getMessage());
                    closeQuietly(session);
                    sessions.remove(session);
                }
            }
        }
    }

    /**
     * 遍历当前所有在线连接（供心跳/统计使用，不修改注册表）。
     *
     * @param visitor 对每个在线会话执行的动作
     */
    public void forEachSession(Consumer<WebSocketSession> visitor) {
        sessionsByUser.values().forEach(
                sessions -> sessions.forEach(session -> {
                    if (session.isOpen()) {
                        visitor.accept(session);
                    }
                }));
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // 连接已失效时 close 自身也可能抛错，忽略
        }
    }
}
