package qg.qgent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 设置请求ID的过滤器
 * RequestIdFilter
 * 最高优先级执行，确保安全过滤链与幂等过滤器读取 requestId 时已就绪。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 最先执行
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = "req_" + UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        chain.doFilter(request, response);
    }
}
