package qg.qgent.common;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "error", Map.of("code", exception.code(), "message", exception.getMessage())));
    }
}
