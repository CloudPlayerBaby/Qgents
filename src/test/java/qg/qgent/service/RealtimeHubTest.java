package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import qg.qgent.websocket.RealtimeFrame;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WebSocket 实时连接注册表：注册/注销/多会话推送/发送失败清理测试。 */
class RealtimeHubTest {

    private final RealtimeHub hub = new RealtimeHub(new ObjectMapper().findAndRegisterModules());

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    /** 同一用户多个设备/标签在线时，fan-out 应推送给全部在线连接。 */
    @Test
    void broadcastDeliversToAllSessionsOfSameUser() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession s1 = openSession();
        WebSocketSession s2 = openSession();
        hub.register(userId, s1);
        hub.register(userId, s2);

        hub.broadcastToUsers(Set.of(userId), RealtimeFrame.of("message.created", "project",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, null, Map.of()));

        verify(s1).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        verify(s2).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    /** 目标用户范围外的在线会话不应收到推送。 */
    @Test
    void broadcastOnlyReachesTargetUsers() throws Exception {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        WebSocketSession targetSession = openSession();
        WebSocketSession otherSession = openSession();
        hub.register(target, targetSession);
        hub.register(other, otherSession);

        hub.broadcastToUsers(Set.of(target), RealtimeFrame.of("notification.created", "notification",
                null, null, null, target.toString(), null, Map.of()));

        verify(targetSession).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        verify(otherSession, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    /** 无目标用户时 fan-out 应为空操作，不抛异常。 */
    @Test
    void broadcastWithEmptyTargetsIsNoOp() {
        hub.broadcastToUsers(Set.of(), RealtimeFrame.of("task.updated", "project", null, null, null, null, null,
                Map.of()));
    }

    /** 发送失败（连接已失效）的连接应被关闭并移除，其余连接仍正常推送。 */
    @Test
    void failedSendClosesAndRemovesSession() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession healthy = openSession();
        WebSocketSession broken = openSession();
        hub.register(userId, healthy);
        hub.register(userId, broken);

        doThrow(new IOException("closed")).when(broken).sendMessage(org.mockito.ArgumentMatchers.any());

        hub.broadcastToUsers(Set.of(userId), RealtimeFrame.of("diff.created", "project", null, null, null, null,
                null, Map.of()));

        verify(healthy).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        // 失效连接被关闭并从注册表移除：再推送不应再尝试向其发送
        org.mockito.Mockito.reset(broken);
        hub.broadcastToUsers(Set.of(userId), RealtimeFrame.of("diff.created", "project", null, null, null, null,
                null, Map.of()));
        verify(broken, org.mockito.Mockito.never())
                .sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    /** 注销是会话级操作：移除一个不会影响该用户其余连接。 */
    @Test
    void unregisterRemovesOnlyThatSession() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession keep = openSession();
        WebSocketSession drop = openSession();
        hub.register(userId, keep);
        hub.register(userId, drop);

        hub.unregister(userId, drop);

        hub.broadcastToUsers(Set.of(userId), RealtimeFrame.of("task.updated", "project", null, null, null, null,
                null, Map.of()));
        verify(keep).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
        verify(drop, org.mockito.Mockito.never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    /** 空 userIds 校验：null 集合安全返回。 */
    @Test
    void broadcastWithNullTargetsIsSafe() {
        hub.broadcastToUsers(null, RealtimeFrame.of("task.updated", "project", null, null, null, null, null,
                Map.of()));
    }

    /** 心跳遍历仅访问在线会话。 */
    @Test
    void forEachSessionVisitsOpenSessions() {
        UUID userId = UUID.randomUUID();
        WebSocketSession online = openSession();
        hub.register(userId, online);
        AtomicInteger visited = new AtomicInteger();
        hub.forEachSession(s -> visited.incrementAndGet());
        assertTrue(visited.get() >= 1);
        assertEquals(1, visited.get());
    }
}
