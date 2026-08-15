package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.DiffReviewRejectRequest;
import qg.qgent.service.DiffReviewBatchService;

import java.util.UUID;

/**
 * Task 级最终 Diff 审核接口
 * 多仓库最终审核：确认后每个仓库独立提交、推送并创建自己的 MR。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/diff-review")
public class TaskDiffReviewController {
    private final DiffReviewBatchService reviews;

    public TaskDiffReviewController(DiffReviewBatchService reviews) {
        this.reviews = reviews;
    }

    /**
     * 契约 §15.3.1：查询 Task 级最终 Diff 审核批次与各仓库交付状态。
     */
    @GetMapping
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
                              @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.get(projectId, taskId, actor), request);
    }

    /**
     * 契约 §15.3.5：查询审核批次中指定 Diff 的 patch 内容。
     */
    @GetMapping("/diffs/{diffId}/patch")
    public ApiResponse<?> patch(@PathVariable UUID projectId, @PathVariable UUID taskId, @PathVariable UUID diffId,
                                @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.patch(projectId, taskId, diffId, actor), request);
    }

    /**
     * 契约 §15.3.2：确认最终 Diff 审核并执行各仓库独立交付（commit、push、创建 MR）。
     */
    @PostMapping("/confirm")
    public ApiResponse<?> confirm(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                  @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.confirm(projectId, taskId, actor), request);
    }

    /**
     * 契约 §15.3.3：拒绝最终 Diff 审核并给出原因。
     */
    @PostMapping("/reject")
    public ApiResponse<?> reject(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                 @AuthenticationPrincipal UUID actor, @Valid @RequestBody DiffReviewRejectRequest body,
                                 HttpServletRequest request) {
        return ok(reviews.reject(projectId, taskId, actor, body.getReason()), request);
    }

    /**
     * 契约 §15.3.4：重试失败仓库的交付（幂等）。
     */
    @PostMapping("/retry-delivery")
    public ApiResponse<?> retry(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(reviews.retryDelivery(projectId, taskId, actor), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
