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

/** Read-only Task timeline endpoint. Artifacts never create an independent Diff or MR. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/artifacts")
public class TaskExecutionArtifactController {
    private final TaskExecutionArtifactService artifacts;

    public TaskExecutionArtifactController(TaskExecutionArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ApiResponse.ok(artifacts.list(projectId, taskId, actor),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
