package qg.qgent.sandboxworker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.sandboxworker.service.ExecutionService;
import qg.qgent.sandboxworker.service.SandboxService;
import qg.qgent.sandboxworker.service.ToolExecutionService;

import java.util.Map;
import java.util.UUID;

/**
 * 供控制层调用的沙箱 Worker 内部接口。
 * 该接口只负责协议校验和响应转换，不得直接暴露给终端用户或公网。
 */
@Validated
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalSandboxController {
    private final SandboxService sandboxService;
    private final ExecutionService executionService;
    private final ToolExecutionService toolExecutionService;

    /**
     * 返回 Worker 进程的基础存活状态。
     *
     * @return 状态为 UP 的健康信息
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * 创建沙箱并应用 Worker 本地资源上限。
     * 相同 sandboxId 和相同请求会返回既有资源，不重复创建底层容器。
     *
     * @param idempotencyKey 控制层生成的幂等键
     * @param request 沙箱归属、Workspace、仓库映射和资源限制
     * @return 已创建或已经存在的沙箱状态
     */
    @PostMapping("/sandboxes")
    @ResponseStatus(HttpStatus.CREATED)
    public SandboxResponse createSandbox(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateSandboxRequest request) {
        return sandboxService.create(request);
    }

    /**
     * 延长沙箱空闲租约，但不突破最大空闲时间和最大生命周期。
     */
    @PostMapping("/sandboxes/{sandboxId}/lease/renew")
    public SandboxResponse renewSandbox(
            @PathVariable UUID sandboxId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestParam(required = false) @Min(1) Long ttlSeconds) {
        return sandboxService.renew(sandboxId, ttlSeconds);
    }

    /**
     * 提交一条兼容版异步参数数组命令，不接受 shell 拼接字符串。
     */
    @PostMapping("/sandboxes/{sandboxId}/executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionResponse createExecution(
            @PathVariable UUID sandboxId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateExecutionRequest request) {
        return executionService.create(sandboxId, request);
    }

    /**
     * 同步执行服务端白名单中的结构化工具，并将结果持久化到 MySQL。
     */
    @PostMapping("/sandboxes/{sandboxId}/tool-executions")
    public ToolExecutionResponse createToolExecution(
            @PathVariable UUID sandboxId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody ToolExecutionRequest request) {
        return toolExecutionService.execute(sandboxId, request);
    }

    /** 查询一条已持久化的工具执行记录。 */
    @GetMapping("/tool-executions/{executionId}")
    public ToolExecutionResponse getToolExecution(@PathVariable UUID executionId) {
        return toolExecutionService.find(executionId);
    }

    /** 按递增游标查询工具执行日志。 */
    @GetMapping("/tool-executions/{executionId}/logs")
    public ExecutionLogsResponse getToolExecutionLogs(
            @PathVariable UUID executionId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return toolExecutionService.logs(executionId, after, limit);
    }

    /** 查询兼容版异步命令状态。 */
    @GetMapping("/executions/{executionId}")
    public ExecutionResponse getExecution(@PathVariable UUID executionId) {
        return executionService.find(executionId);
    }

    /** 取消仍在排队或运行的兼容版异步命令。 */
    @PostMapping("/executions/{executionId}/cancel")
    public ExecutionResponse cancelExecution(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return executionService.cancel(executionId);
    }

    /** 按递增游标查询兼容版异步命令日志。 */
    @GetMapping("/executions/{executionId}/logs")
    public ExecutionLogsResponse getExecutionLogs(
            @PathVariable UUID executionId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return executionService.logs(executionId, after, limit);
    }

    /** 查询沙箱当前状态和租约期限。 */
    @GetMapping("/sandboxes/{sandboxId}")
    public ResponseEntity<SandboxResponse> getSandbox(@PathVariable UUID sandboxId) {
        return sandboxService.find(sandboxId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 取消沙箱内仍活跃的兼容版执行并幂等销毁临时容器。
     * Workspace 独立持久化，因此不会随沙箱删除。
     */
    @DeleteMapping("/sandboxes/{sandboxId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void destroySandbox(
            @PathVariable UUID sandboxId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        executionService.cancelBySandbox(sandboxId);
        sandboxService.destroy(sandboxId);
    }
}
