package qg.qgent.sandboxworker.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

import java.util.stream.Collectors;

/**
 * 将工作节点异常转换为稳定且不泄露内部信息的响应。
 * <p>
 * 框架层协议异常（缺 body、Content-Type 不支持、路径参数类型错误）也必须返回统一
 * {@code {code,message}} 错误体；否则主后端解析不到业务错误码，只能得到 Spring 默认错误页，
 * 排障困难（历史案例：无 Content-Type 的 POST 触发 415）。
 */
@Slf4j
@RestControllerAdvice
public class WorkerExceptionHandler {
    @ExceptionHandler(WorkerException.class)
    public ResponseEntity<WorkerErrorResponse> handleWorkerException(WorkerException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new WorkerErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<WorkerErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        String fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ":" + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(","));
        log.warn("sandbox worker rejected request, invalid fields: {}", fields);
        return ResponseEntity.badRequest()
                .body(new WorkerErrorResponse("INVALID_REQUEST", validationMessage(fields)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<WorkerErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        String fields = exception.getConstraintViolations().stream()
                .map(error -> error.getPropertyPath() + ":" + error.getMessage())
                .distinct()
                .collect(Collectors.joining(","));
        log.warn("sandbox worker rejected request, constraint violations: {}", fields);
        return ResponseEntity.badRequest()
                .body(new WorkerErrorResponse("INVALID_REQUEST", validationMessage(fields)));
    }

    private String validationMessage(String fields) {
        return fields == null || fields.isBlank() ? "请求参数不合法" : "请求参数不合法: " + fields;
    }

    /**
     * Content-Type 不被端点支持（如无 Content-Type 的 POST 命中 @RequestBody）。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<WorkerErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        log.warn("Unsupported media type: {}", exception.getMessage());
        return ResponseEntity.status(415)
                .body(new WorkerErrorResponse("INVALID_MEDIA_TYPE", "请求 Content-Type 不被支持，请使用 application/json"));
    }

    /**
     * 请求体缺失或 JSON 不可解析。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<WorkerErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        log.warn("Unreadable request body: {}", exception.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(new WorkerErrorResponse("INVALID_REQUEST_BODY", "请求体缺失或格式不正确"));
    }

    /**
     * 路径/查询参数类型错误（如非 UUID 路径段）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<WorkerErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Type mismatch: parameter={}", exception.getName());
        return ResponseEntity.badRequest().body(new WorkerErrorResponse("INVALID_ARGUMENT", "请求参数格式不正确"));
    }

    /**
     * 缺少必填查询参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<WorkerErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        log.warn("Missing parameter: {}", exception.getParameterName());
        return ResponseEntity.badRequest().body(new WorkerErrorResponse("MISSING_ARGUMENT", "缺少必填请求参数: " + exception.getParameterName()));
    }

    /**
     * 请求方法不被端点支持（405），保持统一错误体。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<WorkerErrorResponse> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException exception) {
        log.warn("Method not supported: {}", exception.getMessage());
        return ResponseEntity.status(405).body(new WorkerErrorResponse("METHOD_NOT_ALLOWED", "请求方法不被支持"));
    }

    /**
     * 路径不存在（404，Spring 6.1+ NoResourceFoundException），保持统一错误体。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<WorkerErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        return ResponseEntity.status(404).body(new WorkerErrorResponse("NOT_FOUND", "接口路径不存在"));
    }
}
