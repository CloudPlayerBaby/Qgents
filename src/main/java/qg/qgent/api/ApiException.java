package qg.qgent.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * api 相关异常
 * ApiException
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<?> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    /**
     * 可供客户端处理的脱敏结构化错误详情。仅业务已定义且不含敏感内容时使用。
     */
    public ApiException(HttpStatus status, String code, String message, List<?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<?> details() {
        return details;
    }
}
