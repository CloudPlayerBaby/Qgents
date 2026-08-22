package qg.qgent.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import qg.qgent.auth.TokenService;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WebSocket 握手鉴权：query token 校验与 userId 会话属性注入测试。 */
class RealtimeAuthInterceptorTest {

    private final WebSocketHandler handler = mock(WebSocketHandler.class);

    private ServerHttpRequest request(String uri) {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(req.getURI()).thenReturn(URI.create(uri));
        return req;
    }

    /** 合法 token → 握手通过，并把 userId 注入会话属性。 */
    @Test
    void validTokenPassesAndInjectsUserId() {
        TokenService tokens = mock(TokenService.class);
        UUID userId = UUID.randomUUID();
        when(tokens.verifyAccess("abc")).thenReturn(userId);
        RealtimeAuthInterceptor interceptor = new RealtimeAuthInterceptor(tokens);

        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(request("ws://h/api/v1/ws/realtime?token=abc"),
                mock(ServerHttpResponse.class), handler, attributes);

        assertTrue(ok);
        assertEquals(userId, attributes.get(RealtimeAuthInterceptor.USER_ID_ATTR));
    }

    /** 无效/过期 token → 握手拒绝并返回 401，不注入 userId。 */
    @Test
    void invalidTokenRejectsHandshake() {
        TokenService tokens = mock(TokenService.class);
        when(tokens.verifyAccess("bad")).thenReturn(null);
        RealtimeAuthInterceptor interceptor = new RealtimeAuthInterceptor(tokens);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(request("ws://h/api/v1/ws/realtime?token=bad"),
                response, handler, attributes);

        assertFalse(ok);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertNull(attributes.get(RealtimeAuthInterceptor.USER_ID_ATTR));
    }

    /** 缺失 token → 握手拒绝，不调用 token 校验。 */
    @Test
    void missingTokenRejectsHandshake() {
        TokenService tokens = mock(TokenService.class);
        RealtimeAuthInterceptor interceptor = new RealtimeAuthInterceptor(tokens);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        boolean ok = interceptor.beforeHandshake(request("ws://h/api/v1/ws/realtime"),
                response, handler, new HashMap<>());

        assertFalse(ok);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
