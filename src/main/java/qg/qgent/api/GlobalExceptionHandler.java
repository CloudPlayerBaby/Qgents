package qg.qgent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage(), List.of(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", message(f))).toList();
        return ResponseEntity.badRequest().body(error("INVALID_ARGUMENT", "请求参数不合法", details, request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "服务暂时不可用", List.of(), request));
    }

    private String message(FieldError error) {
        return error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage();
    }

    private Map<String, Object> error(String code, String message, List<?> details, HttpServletRequest request) {
        return Map.of("error", Map.of("code", code, "message", message, "details", details),
                "requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
