package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 工作节点统一错误响应。 */
@Data
@AllArgsConstructor
public class WorkerErrorResponse {
    private String code;
    private String message;
}
