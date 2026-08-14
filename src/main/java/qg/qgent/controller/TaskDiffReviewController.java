package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.DiffReviewRejectRequest;
import qg.qgent.service.DiffReviewBatchService;

import java.util.UUID;

/** Task-level final Diff review. A confirmation delivers each repository independently. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/diff-review")
public class TaskDiffReviewController {
    private final DiffReviewBatchService reviews;

    public TaskDiffReviewController(DiffReviewBatchService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.get(projectId, taskId, actor), request);
    }

    @GetMapping("/diffs/{diffId}/patch")
    public ApiResponse<?> patch(@PathVariable UUID projectId, @PathVariable UUID taskId, @PathVariable UUID diffId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.patch(projectId, taskId, diffId, actor), request);
    }

    @PostMapping("/confirm")
    public ApiResponse<?> confirm(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.confirm(projectId, taskId, actor), request);
    }

    @PostMapping("/reject")
    public ApiResponse<?> reject(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, @Valid @RequestBody DiffReviewRejectRequest body,
            HttpServletRequest request) {
        return ok(reviews.reject(projectId, taskId, actor, body.getReason()), request);
    }

    @PostMapping("/retry-delivery")
    public ApiResponse<?> retry(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.retryDelivery(projectId, taskId, actor), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
