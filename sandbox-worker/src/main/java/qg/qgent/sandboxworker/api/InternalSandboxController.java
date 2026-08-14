package qg.qgent.sandboxworker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.sandboxworker.service.SandboxService;
import qg.qgent.sandboxworker.service.ToolExecutionService;
import qg.qgent.sandboxworker.service.TestExecutionService;
import qg.qgent.sandboxworker.workspace.WorkspaceManagerService;

import java.util.Map;
import java.util.UUID;

/**
 * 供控制层调用的 Sandbox Worker 内部接口。
 * 接口只负责协议校验和响应转换，不得直接暴露给终端用户或公网。
 */
@Validated
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalSandboxController {
    private final SandboxService sandboxService;
    private final ToolExecutionService toolExecutionService;
    private final TestExecutionService testExecutionService;
    private final WorkspaceManagerService workspaceManagerService;

    /** 返回 Worker 进程的基础存活状态。 */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** 创建 Sandbox 并应用 Worker 本地资源上限。 */
    @PostMapping("/sandboxes")
    @ResponseStatus(HttpStatus.CREATED)
    public SandboxResponse createSandbox(@Valid @RequestBody CreateSandboxRequest request) {
        return sandboxService.create(request);
    }

    /**
     * 延长 Sandbox 空闲租约，但不突破最大空闲时间和最大生命周期。
     */
    @PostMapping("/sandboxes/{sandboxId}/lease/renew")
    public SandboxResponse renewSandbox(
            @PathVariable UUID sandboxId,
            @RequestParam(required = false) @Min(1) Long ttlSeconds) {
        return sandboxService.renew(sandboxId, ttlSeconds);
    }

    /**
     * 提交结构化工具执行。
     * 请求写入 MySQL 后返回 202，调用方通过查询和日志接口跟踪后台执行结果。
     */
    @PostMapping("/sandboxes/{sandboxId}/tool-executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ToolExecutionResponse createToolExecution(
            @PathVariable UUID sandboxId,
            @Valid @RequestBody ToolExecutionRequest request) {
        return toolExecutionService.submit(sandboxId, request);
    }

    /**
     * 查询持久化工具执行记录。
     */
    @GetMapping("/tool-executions/{executionId}")
    public ToolExecutionResponse getToolExecution(@PathVariable UUID executionId) {
        return toolExecutionService.find(executionId);
    }

    /**
     * 取消仍处于排队或运行状态的工具执行。
     */
    @PostMapping("/tool-executions/{executionId}/cancel")
    public ToolExecutionResponse cancelToolExecution(@PathVariable UUID executionId) {
        return toolExecutionService.cancel(executionId);
    }

    /**
     * 按递增游标查询工具执行日志。
     */
    @GetMapping("/tool-executions/{executionId}/logs")
    public ExecutionLogsResponse getToolExecutionLogs(
            @PathVariable UUID executionId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return toolExecutionService.logs(executionId, after, limit);
    }

    /**
     * 查询 Sandbox 当前状态和租约期限。
     */
    @GetMapping("/sandboxes/{sandboxId}")
    public ResponseEntity<SandboxResponse> getSandbox(@PathVariable UUID sandboxId) {
        return sandboxService.find(sandboxId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 取消 Sandbox 内仍活跃的工具执行并销毁临时容器。
     * 持久 Workspace 不会随 Sandbox 删除。
     */
    @DeleteMapping("/sandboxes/{sandboxId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void destroySandbox(@PathVariable UUID sandboxId) {
        toolExecutionService.cancelBySandbox(sandboxId);
        sandboxService.destroy(sandboxId);
    }

    /** 同步执行一组已验证 Testset，返回脱敏结果。 */
    @PostMapping("/test-executions")
    public TestExecutionResponse executeTests(@Valid @RequestBody TestExecutionRequest request) {
        return testExecutionService.execute(request);
    }

    /** 基于共享 Git Store 做只读合并预演，不创建 Commit。 */
    @PostMapping("/merge-previews")
    public MergePreviewResponse mergePreview(@Valid @RequestBody MergePreviewRequest request) {
        return workspaceManagerService.mergePreview(request.getRepositoryId(), request.getSourceRef(),
                request.getTargetBranch());
    }

    /** 在受控 Git Store 中把引用解析为固定 commit SHA。 */
    @PostMapping("/git-resolutions")
    public GitResolveResponse resolveGitRef(@Valid @RequestBody GitResolveRequest request) {
        return new GitResolveResponse(workspaceManagerService.resolveGitRef(request.getRepositoryId(), request.getRef()));
    }
}
