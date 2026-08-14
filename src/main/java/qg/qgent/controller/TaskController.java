package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.*;
import qg.qgent.dto.*;
import qg.qgent.service.TaskService;
import java.util.*;

/**
 * Task 与 TaskStep 计划接口（§11.3）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /**
     * 从 ACTIVE REQUIREMENT 群创建 Task（可复用前序 Task 的 Workspace）。
     */
    @Operation(summary = "创建 Task")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
            @Valid @RequestBody TaskCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId, actor, body), request);
    }

    /** 查询项目可见的 Task。 */
    @Operation(summary = "查询项目 Task 列表")
    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
            HttpServletRequest request) {
        return ok(service.list(projectId, actor), request);
    }

    /** 获取 Task、Workspace 与 repository 范围。 */
    @Operation(summary = "获取 Task 详情")
    @GetMapping("/{taskId}")
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.get(projectId, taskId, actor), request);
    }

    /** 取消整个 Task（202 异步受理）。 */
    @Operation(summary = "取消 Task")
    @PostMapping("/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> cancel(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.cancel(projectId, taskId, actor), request);
    }

    /** 写入 Planner 生成的 TaskStep 计划。 */
    @Operation(summary = "写入 TaskStep 计划")
    @PostMapping("/{taskId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> addSteps(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, @Valid @RequestBody List<@Valid TaskStepCreateRequest> body,
            HttpServletRequest request) {
        return ok(service.addSteps(projectId, taskId, actor, body), request);
    }

    /** 在步骤 PENDING 时更换执行 Agent。 */
    @Operation(summary = "更换步骤执行 Agent")
    @PostMapping("/{taskId}/steps/{stepId}/replace-agent")
    public ApiResponse<?> replaceAgent(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @PathVariable UUID stepId, @AuthenticationPrincipal UUID actor,
            @Valid @RequestBody TaskAgentUpdateRequest body, HttpServletRequest request) {
        return ok(service.replaceAssignedAgent(projectId, taskId, stepId, actor, body.getAssignedAgentId()), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
