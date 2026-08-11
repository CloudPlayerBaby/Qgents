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
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.CqDecisionRequest;
import qg.qgent.dto.MergeRequestCheckResponse;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.MergeRequestDetailResponse;
import qg.qgent.dto.MergeRequestReviewResponse;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.service.MergeRequestService;

import java.util.List;
import java.util.UUID;

/**
 * MR 镜像、审查与质量状态端点（13）。
 * 创建/同步/合并为异步受理返回 202（真实 GitHub 操作为接缝）；CQ 审查为同步决策返回 200。
 * POST 写接口均需 Idempotency-Key；CQ 审查者不得是 MR 作者或交付物创建者。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/merge-requests")
public class MergeRequestController {
    private final MergeRequestService mergeRequestService;

    public MergeRequestController(MergeRequestService mergeRequestService) {
        this.mergeRequestService = mergeRequestService;
    }

    /** 查询项目关联 MR，支持仓库、需求群、状态过滤。 */
    @GetMapping
    public ApiPageResponse<MergeRequestSummaryResponse> list(@PathVariable UUID projectId,
            @AuthenticationPrincipal UUID userId, @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID groupId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return mergeRequestService.list(projectId, userId, repositoryId, groupId, status, cursor, limit,
                requestId(request));
    }

    /** 基于已接受交付物创建 MR。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody MergeRequestCreateRequest body, HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.create(projectId, userId, body);
        return ok(data, request);
    }

    /** 查询 MR、关联需求群、检查与审查摘要及质量门禁汇总。 */
    @GetMapping("/{mergeRequestId}")
    public ApiResponse<?> detail(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestDetailResponse data = mergeRequestService.detail(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /** 查询门禁检查详情。 */
    @GetMapping("/{mergeRequestId}/checks")
    public ApiResponse<?> checks(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        List<MergeRequestCheckResponse> data = mergeRequestService.checks(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /** 查询人工与 AI 审查摘要。 */
    @GetMapping("/{mergeRequestId}/reviews")
    public ApiResponse<?> reviews(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        List<MergeRequestReviewResponse> data = mergeRequestService.reviews(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /** 触发从 GitHub 同步 MR 最新状态。 */
    @PostMapping("/{mergeRequestId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> sync(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.sync(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /** 提交一次 CQ+1 审查（非作者、非交付物创建者）。 */
    @PostMapping("/{mergeRequestId}/cq-approvals")
    public ApiResponse<?> cqApproval(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, @RequestBody(required = false) CqDecisionRequest body,
            HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.cqApproval(projectId, mergeRequestId, userId,
                body == null ? null : body.getReason());
        return ok(data, request);
    }

    /** 拒绝 CQ 并给出修改意见（非作者、非交付物创建者）。 */
    @PostMapping("/{mergeRequestId}/cq-rejections")
    public ApiResponse<?> cqRejection(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody CqDecisionRequest body,
            HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.cqRejection(projectId, mergeRequestId, userId,
                body.getReason());
        return ok(data, request);
    }

    /** 通过质量门禁后执行合并（需 Project Admin）。 */
    @PostMapping("/{mergeRequestId}/merge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> merge(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.merge(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
