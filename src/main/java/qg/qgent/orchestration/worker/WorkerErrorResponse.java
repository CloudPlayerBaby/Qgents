package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Worker 统一错误响应体（镜像 Worker 的 WorkerErrorResponse）。
 * 客户端把非 2xx 响应解析为本结构，保留 Worker 返回的业务错误码，供端口区分工具级失败与基础设施失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerErrorResponse {

    /** 稳定错误码，如 FILE_HASH_MISMATCH、EXECUTION_NOT_FOUND。 */
    private String code;

    /** 不泄露内部信息的错误说明。 */
    private String message;
}
