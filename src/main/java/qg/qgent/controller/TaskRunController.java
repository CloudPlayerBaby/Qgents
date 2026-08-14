package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.ExecutionContextResponse;
import qg.qgent.dto.InputDecisionRequest;
import qg.qgent.dto.InputReplyRequest;
import qg.qgent.dto.InputRequestResponse;
import qg.qgent.dto.LogEntryResponse;
import qg.qgent.dto.TaskRunDetailResponse;
import qg.qgent.dto.TaskRunListItemResponse;
import qg.qgent.dto.TaskRunSummaryResponse;
import qg.qgent.service.TaskRunService;

import java.util.List;
import java.util.UUID;

/**
 * TaskRun 与人工输入/审批接口（§12.2）。
 * retry/cancel 异步受理返回 202；输入/审批决策同步返回 200。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "12.2 任务运行与执行上下文", description = "查询 TaskRun、执行日志和人工输入/审批请求")
public class TaskRunController {
    private final TaskRunService taskRunService;

    public TaskRunController(TaskRunService taskRunService) {
        this.taskRunService = taskRunService;
    }

    /** 查询 Task 的执行记录。 */
    @GetMapping("/tasks/{taskId}/task-runs")
    public ApiPageResponse<TaskRunListItemResponse> listByTask(@PathVariable UUID projectId,
            @PathVariable UUID taskId, @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return taskRunService.listByTask(projectId, taskId, userId, cursor, limit, requestId(request));
    }

    /** 获取单次运行的详情与产物摘要。 */
    @GetMapping("/task-runs/{taskRunId}")
    public ApiResponse<?> detail(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        TaskRunDetailResponse data = taskRunService.detail(projectId, taskRunId, userId);
        return ok(data, request);
    }

    /** 为失败或已取消的运行创建新的 TaskRun。 */
    @PostMapping("/task-runs/{taskRunId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> retry(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        TaskRunSummaryResponse data = taskRunService.retry(projectId, taskRunId, userId);
        return ok(data, request);
    }

    /** 取消未完成运行，服务端仅在安全检查点终止。 */
    @PostMapping("/task-runs/{taskRunId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> cancel(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        TaskRunSummaryResponse data = taskRunService.cancel(projectId, taskRunId, userId);
        return ok(data, request);
    }

    /** 游标读取已脱敏的执行日志。 */
    @GetMapping("/task-runs/{taskRunId}/logs")
    public ApiPageResponse<LogEntryResponse> logs(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return taskRunService.logs(projectId, taskRunId, userId, cursor, limit, requestId(request));
    }

    /** 读取 Workspace 与 Sandbox 的只读状态摘要。 */
    @GetMapping("/task-runs/{taskRunId}/execution-context")
    public ApiResponse<?> executionContext(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        ExecutionContextResponse data = taskRunService.executionContext(projectId, taskRunId, userId);
        return ok(data, request);
    }

    /** 查询运行期间发起的人机输入请求。 */
    @GetMapping("/task-runs/{taskRunId}/input-requests")
    public ApiResponse<?> inputRequests(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        List<InputRequestResponse> data = taskRunService.inputRequests(projectId, taskRunId, userId);
        return ok(data, request);
    }

    /** 回答 WAITING_INPUT 输入请求。 */
    @PostMapping("/task-runs/{taskRunId}/input-requests/{requestId}/reply")
    public ApiResponse<?> reply(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @PathVariable UUID requestId, @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody InputReplyRequest body, HttpServletRequest request) {
        InputRequestResponse data = taskRunService.replyInput(projectId, taskRunId, requestId, userId,
                body.getAnswer());
        return ok(data, request);
    }

    /** 批准 WAITING_APPROVAL 审批请求（需 Project Admin）。 */
    @PostMapping("/task-runs/{taskRunId}/input-requests/{requestId}/approve")
    public ApiResponse<?> approve(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @PathVariable UUID requestId, @AuthenticationPrincipal UUID userId,
            @RequestBody(required = false) InputDecisionRequest body, HttpServletRequest request) {
        InputRequestResponse data = taskRunService.approveInput(projectId, taskRunId, requestId, userId,
                body == null ? null : body.getReason());
        return ok(data, request);
    }

    /** 拒绝 WAITING_APPROVAL 审批请求（需 Project Admin）。 */
    @PostMapping("/task-runs/{taskRunId}/input-requests/{requestId}/reject")
    public ApiResponse<?> reject(@PathVariable UUID projectId, @PathVariable UUID taskRunId,
            @PathVariable UUID requestId, @AuthenticationPrincipal UUID userId,
            @RequestBody(required = false) InputDecisionRequest body, HttpServletRequest request) {
        InputRequestResponse data = taskRunService.rejectInput(projectId, taskRunId, requestId, userId,
                body == null ? null : body.getReason());
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
