package qg.qgent.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
                .body(error(ex.code(), ex.getMessage(), List.of(), request));
    }

    // 处理请求参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // 获取请求参数校验异常的详细信息
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", message(f))).toList();

        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求参数不合法", details, request));
    }

    // 处理参数类型转换失败（如 path/query 中的 UUID 格式错误）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求参数格式不正确", List.of(), request));
    }

    // 处理请求体 JSON 解析失败
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // 返回 400 错误
        return ResponseEntity
                .badRequest()
                .body(error("INVALID_ARGUMENT", "请求体格式不正确", List.of(), request));
    }

    // 处理其他异常
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
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
