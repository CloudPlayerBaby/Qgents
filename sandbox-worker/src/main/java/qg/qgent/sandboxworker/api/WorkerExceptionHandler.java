package qg.qgent.sandboxworker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将工作节点异常转换为稳定且不泄露内部信息的响应。 */
@RestControllerAdvice
public class WorkerExceptionHandler {
    @ExceptionHandler(WorkerException.class)
    public ResponseEntity<WorkerErrorResponse> handleWorkerException(WorkerException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new WorkerErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<WorkerErrorResponse> handleValidationException() {
        return ResponseEntity.badRequest().body(new WorkerErrorResponse("INVALID_REQUEST", "请求参数不合法"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<WorkerErrorResponse> handleConstraintViolation() {
        return ResponseEntity.badRequest().body(new WorkerErrorResponse("INVALID_REQUEST", "请求参数不合法"));
    }
}
