package qg.qgent.api;

import org.springframework.http.HttpStatus;

/**
 * 表示业务状态必须提交后再返回给客户端的错误，例如首次发现邀请已经过期。
 */
public class PersistedApiException extends ApiException {
    public PersistedApiException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
