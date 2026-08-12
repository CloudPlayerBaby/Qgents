package qg.qgent.sandboxworker.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 内部接口可预期的业务异常。 */
@Getter
public class WorkerException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
