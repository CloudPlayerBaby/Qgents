package qg.qgent.orchestration.worker;

import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;

/**
 * Sandbox Worker HTTP 传输层失败。
 *
 * <p>公开错误码始终保持 {@code SANDBOX_WORKER_UNAVAILABLE}，以兼容客户端；诊断码只描述
 * 安全的网络类别，不携带端点、凭据或底层异常原文。</p>
 */
public final class SandboxWorkerTransportException extends ApiException {
    private final String diagnosticCode;

    public SandboxWorkerTransportException(String diagnosticCode, String message) {
        super(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", message);
        this.diagnosticCode = diagnosticCode;
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }
}
