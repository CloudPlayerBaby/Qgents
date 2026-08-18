package qg.qgent.sandboxworker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker 内部 API 的兜底路由。
 * <p>
 * Spring 在未命中 Controller 时可能直接写出空 404，绕过 {@link WorkerExceptionHandler}；主后端
 * 因而无法取得稳定错误码。仅覆盖内部 API 前缀，既不拦截 Actuator，也不影响更具体的业务路由。
 */
@RestController
class WorkerFallbackController {

    @RequestMapping("/internal/v1/**")
    ResponseEntity<WorkerErrorResponse> notFound() {
        return ResponseEntity.status(404).body(new WorkerErrorResponse("NOT_FOUND", "接口路径不存在"));
    }
}
