package qg.qgent.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import qg.qgent.auth.AuthCookieService;
import qg.qgent.auth.TokenService;

import java.util.Map;
import java.util.UUID;
import java.net.HttpCookie;

/**
 * WebSocket 握手鉴权拦截器（契约 §12.1 补充）。
 * <p>
 * 浏览器 WebSocket 升级握手自动携带同站 HttpOnly Cookie。这里从 Cookie 读取 access token 并用
 * {@link TokenService#verifyAccess} 校验，
 * 通过则将 {@code userId} 放入会话属性（后续 Handler 读取建立连接）；token 缺失或无效则拒绝握手。
 * <p>
 * 安全约束：调用方不得在 URL、日志或异常信息中携带认证凭证。
 */
@Component
public class RealtimeAuthInterceptor implements HandshakeInterceptor {
    /**
     * 会话属性中存放已鉴权用户 ID 的键。
     */
    public static final String USER_ID_ATTR = "realtimeUserId";

    private final TokenService tokenService;

    public RealtimeAuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = cookie(request, AuthCookieService.ACCESS_COOKIE);
        UUID userId = token == null ? null : tokenService.verifyAccess(token);
        if (userId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID_ATTR, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 默认实现即可：beforeHandshake 已处理鉴权决策
    }

    private String cookie(ServerHttpRequest request, String name) {
        try {
            for (String value : request.getHeaders().getValuesAsList(HttpHeaders.COOKIE)) {
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        return cookie.getValue();
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 畸形 Cookie 头一律视为未认证，不能导致握手升级为 500。
        }
        return null;
    }
}
