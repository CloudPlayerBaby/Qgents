package qg.qgent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import qg.qgent.websocket.RealtimeAuthInterceptor;
import qg.qgent.websocket.WebSocketRealtimeHandler;

/**
 * WebSocket 实时通道配置（单连接 + 用户级聚合流，契约 §12.1 补充）。
 * <p>
 * 注册 {@code /api/v1/ws/realtime} 端点：客户端通过 HttpOnly access Cookie 完成握手鉴权后，
 * 服务端将当前用户可见项目/团队事件与本人通知聚合并经单条连接实时推送（SSE 三端点保留兼容）。
 */
@Configuration
@EnableWebSocket
public class RealtimeWsConfig implements WebSocketConfigurer {

    private final WebSocketRealtimeHandler realtimeHandler;
    private final RealtimeAuthInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public RealtimeWsConfig(WebSocketRealtimeHandler realtimeHandler, RealtimeAuthInterceptor authInterceptor,
                            @Value("${app.cors-allowed-origins}") String origins) {
        this.realtimeHandler = realtimeHandler;
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = java.util.Arrays.stream(origins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
        if (java.util.Arrays.asList(this.allowedOrigins).contains("*")) {
            throw new IllegalStateException("Cookie 认证不允许 WebSocket 使用通配来源");
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeHandler, "/api/v1/ws/realtime")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
