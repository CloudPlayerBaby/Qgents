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
 * Project-scoped task and planned workflow-step API; no endpoint directly
 * controls Git, Workspace or Sandbox.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /**
     * Creates one task and its task-level workspace metadata; execution remains
     * asynchronous and externally controlled.
     */
    @Operation(summary = "Create a task")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
            @Valid @RequestBody TaskCreateRequest body, HttpServletRequest request) {
        return ok(service.create(projectId, actor, body), request);
    }

    /** Lists tasks visible to the authenticated project member. */
    @Operation(summary = "List project tasks")
    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
            HttpServletRequest request) {
        return ok(service.list(projectId, actor), request);
    }

    /**
     * Returns a task including workspace and repository identifiers, without host
     * paths.
     */
    @Operation(summary = "Get task")
    @GetMapping("/{taskId}")
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.get(projectId, taskId, actor), request);
    }

    /** Persists Planner output as ordered dependency-aware steps. */
    @Operation(summary = "Add planned task steps")
    @PostMapping("/{taskId}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> addSteps(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, @Valid @RequestBody List<@Valid TaskStepCreateRequest> body,
            HttpServletRequest request) {
        return ok(service.addSteps(projectId, taskId, actor, body), request);
    }

    /** Replaces the selected Agent only before the step starts. */
    @Operation(summary = "Replace pending step Agent")
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
