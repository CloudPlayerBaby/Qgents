package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.service.TaskExecutionArtifactService;

import java.util.UUID;

/**
 * Task 执行产物（执行时间线）查询接口
 * 只读查询 Task 的执行产物卡片；产物不构成独立 Diff 或 MR 交付物。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/artifacts")
public class TaskExecutionArtifactController {
    private final TaskExecutionArtifactService artifacts;

    public TaskExecutionArtifactController(TaskExecutionArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    /**
     * 契约 §15.6.1：查询 Task 的执行产物时间线（按 Task 内序号升序，含 PLAN 与 Run 产物）。
     */
    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ApiResponse.ok(artifacts.list(projectId, taskId, actor),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
