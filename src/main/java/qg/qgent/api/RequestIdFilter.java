package qg.qgent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * 设置请求ID的过滤器
 * RequestIdFilter
 * 最高优先级执行，确保安全过滤链与幂等过滤器读取 requestId 时已就绪。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 最先执行
@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = "req_" + UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        MDC.put(ATTRIBUTE, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // 访问日志：方法、URI、状态码、耗时；SSE 等长连接在连接结束时才输出，耗时即连接时长。
            log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), Duration.ofNanos(System.nanoTime() - started).toMillis());
            // Tomcat reuses request threads; do not leak this ID to the next request.
            MDC.remove(ATTRIBUTE);
        }
    }
}
