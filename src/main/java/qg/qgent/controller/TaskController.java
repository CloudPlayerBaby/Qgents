package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.TaskAgentUpdateRequest;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.dto.TaskListItemResponse;
import qg.qgent.dto.TaskStepCreateRequest;
import qg.qgent.dto.TaskStepListItemResponse;
import qg.qgent.service.TaskDisplayService;
import qg.qgent.service.TaskService;

import java.util.List;
import java.util.UUID;

/**
 * 任务与任务步骤接口
 * 任务中心展示摘要（列表/详情/步骤，{@link TaskDisplayService}）与任务写操作（创建/取消/写入计划/更换 Agent，{@link TaskService}）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {
    private final TaskService service;
    private final TaskDisplayService display;

    public TaskController(TaskService service, TaskDisplayService display) {
        this.service = service;
        this.display = display;
    }

    /**
     * 契约 §11.3：从 ACTIVE REQUIREMENT 群创建 Task（可复用前序 Task 的 Workspace）。
     */
    @Operation(summary = "创建 Task")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
                                 @Valid @RequestBody TaskCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId, actor, body), request);
    }

    /**
     * 契约 §16.1：任务中心列表，游标分页并支持 groupId/status/createdBy/repositoryId/keyword 筛选。
     * keyword 按不区分大小写包含匹配 displayCode/title/requirement/需求群名/创建人/绑定仓库展示名与全名。
     * 返回卡片摘要 DTO，避免前端逐条发起 Group/Member/Repository/Agent 查询。
     */
    @Operation(summary = "查询项目 Task 列表")
    @GetMapping
    public PagedApiResponse<TaskListItemResponse> list(@PathVariable UUID projectId,
                                                       @AuthenticationPrincipal UUID actor, @RequestParam(required = false) String groupId,
                                                       @RequestParam(required = false) String status, @RequestParam(required = false) String createdBy,
                                                       @RequestParam(required = false) String repositoryId, @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return display.list(projectId, actor, groupId, status, createdBy, repositoryId, keyword, cursor, limit,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §16.2：任务详情，完整上下文摘要（验收标准/Workspace/操作能力/产物统计/总 Diff/来源消息）。
     */
    @Operation(summary = "获取 Task 详情")
    @GetMapping("/{taskId}")
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
                              @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(display.detail(projectId, taskId, actor), request);
    }

    /**
     * 契约 v1.8.0 §20（N01）：任务执行流程步骤列表（统一 cursor envelope）。
     */
    @Operation(summary = "查询任务执行步骤")
    @GetMapping("/{taskId}/steps")
    public PagedApiResponse<TaskStepListItemResponse> steps(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                                            @AuthenticationPrincipal UUID actor,
                                                            HttpServletRequest request) {
        return display.steps(projectId, taskId, actor,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §11.3：取消整个 Task（202 异步受理）。
     */
    @Operation(summary = "取消 Task")
    @PostMapping("/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> cancel(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.cancel(projectId, taskId, actor), request);
    }

    /**
     * 契约 §11.3：写入 Planner 生成的 TaskStep 计划。
     */
    @Operation(summary = "写入 TaskStep 计划")
    @PostMapping("/{taskId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> addSteps(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                   @AuthenticationPrincipal UUID actor, @Valid @RequestBody List<@Valid TaskStepCreateRequest> body,
                                   HttpServletRequest request) {
        return ok(service.addSteps(projectId, taskId, actor, body), request);
    }

    /**
     * 契约 §11.3：在步骤 PENDING 时更换执行 Agent。
     */
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
