package qg.qgent.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.auth.TokenService;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket 握手鉴权拦截器（契约 §12.1 补充）。
 * <p>
 * 浏览器 WebSocket 升级握手无法携带 Authorization 头，故 access token 走握手 URL 的
 * {@code ?token=<accessToken>} 查询参数。这里用 {@link TokenService#verifyAccess} 校验 token，
 * 通过则将 {@code userId} 放入会话属性（后续 Handler 读取建立连接）；token 缺失或无效则拒绝握手。
 * <p>
 * 安全约束：该 token 为短期 access token，与现有 JWT 生命周期一致；调用方不得把完整握手 URL
 * （含 token）打入日志或异常信息，避免凭证进日志。
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
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");
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
}
