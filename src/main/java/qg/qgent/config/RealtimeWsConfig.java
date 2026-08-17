package qg.qgent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import qg.qgent.websocket.RealtimeAuthInterceptor;
import qg.qgent.websocket.WebSocketRealtimeHandler;

/**
 * WebSocket 实时通道配置（单连接 + 用户级聚合流，契约 §12.1 补充）。
 * <p>
 * 注册 {@code /api/v1/ws/realtime} 端点：客户端以 {@code ?token=<accessToken>} 完成握手鉴权后，
 * 服务端将当前用户可见项目/团队事件与本人通知聚合并经单条连接实时推送（SSE 三端点保留兼容）。
 */
@Configuration
@EnableWebSocket
public class RealtimeWsConfig implements WebSocketConfigurer {

    private final WebSocketRealtimeHandler realtimeHandler;
    private final RealtimeAuthInterceptor authInterceptor;

    public RealtimeWsConfig(WebSocketRealtimeHandler realtimeHandler, RealtimeAuthInterceptor authInterceptor) {
        this.realtimeHandler = realtimeHandler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeHandler, "/api/v1/ws/realtime")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");
    }
}
