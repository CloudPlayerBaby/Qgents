package qg.qgent.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * 全局异常处理
 * GlobalExceptionHandler
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 处理 ApiException 异常
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException ex, HttpServletRequest request) {
        log.warn("API Exception [{}]: {} - {}", request.getRequestURI(), ex.code(), ex.getMessage());
        return ResponseEntity
                .status(ex.status())
                .body(error(ex.code(), ex.getMessage(), ex.details(), request));
    }

    // 处理请求参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // 获取请求参数校验异常的详细信息
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", message(f))).toList();
        log.warn("Validation rejected [{}]: {}", request.getRequestURI(), details);

        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求参数不合法", details, request));
    }

    // 处理参数类型转换失败（如 path/query 中的 UUID 格式错误）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Type mismatch [{}]: parameter={}", request.getRequestURI(), ex.getName());
        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求参数格式不正确", List.of(), request));
    }

    // 处理请求体 JSON 解析失败
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Unreadable body [{}]: {}", request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求体格式不正确", List.of(), request));
    }

    // 处理异步请求（SSE 流）客户端断开：连接已失效，响应无法再写入任何错误体。
    // 浏览器刷新/切换页面/网络中断都会触发，属客户端常态，只记 info，交由容器完成派发收尾。
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void asyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.info("Async request (SSE) client disconnected [{}]: {}", request.getRequestURI(), ex.getMessage());
    }

    // 未映射的路径/方法：路径不存在（404）或方法不匹配（405）。必须在 Exception 兜底之前命中，
    // 否则 NoResourceFoundException 等会被吞成 500「服务暂时不可用」，掩盖「端点不存在」的真实问题。
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found [{}] {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("NOT_FOUND", "接口路径不存在", List.of(), request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("Method not supported [{}] {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(error("METHOD_NOT_ALLOWED", "请求方法不允许", List.of(), request));
    }

    // 处理其他异常
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        // SSE 等流式响应已提交后再抛出的异常：写 JSON 错误体只会引发二次
        // HttpMessageNotWritableException，因此只记录日志、不再写响应。
        if (response.isCommitted()) {
            log.info("Exception after response committed [{}]: {}", request.getRequestURI(), ex.getMessage());
            return null;
        }
        log.error("Unexpected error [{}]", request.getRequestURI(), ex);
        // 返回 500 错误
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "服务暂时不可用", List.of(), request));
    }

    // 获取error的message，如果没有则返回默认值"不合法"
    private String message(FieldError error) {
        return error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage();
    }

    // 按照指定格式返回错误信息
    private Map<String, Object> error(String code, String message, List<?> details, HttpServletRequest request) {
        return Map.of("error", Map.of("code", code, "message", message, "details", details),
                "requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
