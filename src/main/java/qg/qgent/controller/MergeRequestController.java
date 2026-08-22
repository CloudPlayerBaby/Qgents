package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.MergeRequestCommentService;
import qg.qgent.service.MergeRequestService;
import qg.qgent.service.MrPreflightService;

import java.util.List;
import java.util.UUID;

/**
 * MR、审查与质量状态接口
 * 创建/同步/合并为异步受理返回 202；CQ 审查为同步决策返回 200。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/merge-requests")
public class MergeRequestController {
    private final MergeRequestService mergeRequestService;
    private final MergeRequestCommentService commentService;
    private final MrPreflightService preflightService;

    public MergeRequestController(MergeRequestService mergeRequestService,
                                  MergeRequestCommentService commentService,
                                  MrPreflightService preflightService) {
        this.mergeRequestService = mergeRequestService;
        this.commentService = commentService;
        this.preflightService = preflightService;
    }

    /**
     * 契约 §13：查询项目关联 MR，支持仓库、需求群、状态过滤。
     */
    @GetMapping
    public ApiPageResponse<MergeRequestSummaryResponse> list(@PathVariable UUID projectId,
                                                             @AuthenticationPrincipal UUID userId, @RequestParam(required = false) UUID repositoryId,
                                                             @RequestParam(required = false) UUID groupId, @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
                                                             HttpServletRequest request) {
        return mergeRequestService.list(projectId, userId, repositoryId, groupId, status, cursor, limit,
                requestId(request));
    }

    /**
     * 申请分支级 MR 预检（DIFF_FIRST 手动入口）。后端从 Workspace 解析 source/target branch，
     * 持久化预检请求并启动 Dry Run；真实 MR 只在 Dry Run 通过且独立 CQ+1 通过后由服务端自动创建。
     */
    @PostMapping("/preflight")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> requestPreflight(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId,
                                           @Valid @RequestBody MergeRequestPreflightRequest body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           HttpServletRequest request) {
        MergeRequestPreflightResponse data = preflightService.requestPreflight(projectId, userId,
                body.getTaskId(), body.getRepositoryId(), idempotencyKey);
        return ok(data, request);
    }

    /**
     * 重新预检：CQ 拒绝或失败后创建全新 Dry Run，让旧 CQ+1 失效。
     */
    @PostMapping("/preflight/{preflightId}/retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> retryPreflight(@PathVariable UUID projectId, @PathVariable UUID preflightId,
                                         @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestPreflightResponse data = preflightService.retryPreflight(projectId, preflightId, userId);
        return ok(data, request);
    }

    /**
     * 查询单条分支级预检申请（含覆盖任务/Diff、Dry Run、CQ 与真实 MR 状态）。
     */
    @GetMapping("/preflight/{preflightId}")
    public ApiResponse<?> preflight(@PathVariable UUID projectId, @PathVariable UUID preflightId,
                                    @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestPreflightResponse data = preflightService.getPreflight(projectId, preflightId, userId);
        return ok(data, request);
    }

    /**
     * 兼容入口：旧客户端 {@code POST /merge-requests} 语义改为转发到“申请预检”，不再直接创建真实 MR。
     * 目标分支与标题由服务端从 Workspace 读取，客户端传入值仅作兼容忽略。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> create(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId,
                                 @Valid @RequestBody MergeRequestCreateRequest body,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 HttpServletRequest request) {
        MergeRequestPreflightResponse data = preflightService.requestPreflight(projectId, userId,
                body.getTaskId(), body.getRepositoryId(), idempotencyKey);
        return ok(data, request);
    }

    /**
     * 契约 §13：查询 MR 详情、检查与审查摘要。
     */
    @GetMapping("/{mergeRequestId}")
    public ApiResponse<?> detail(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                 @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestDetailResponse data = mergeRequestService.detail(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /**
     * 契约 §13 / §21：查询门禁检查汇总（{status, requiredChecks, items[]}）。
     */
    @GetMapping("/{mergeRequestId}/checks")
    public ApiResponse<?> checks(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                 @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestChecksResponse data = mergeRequestService.checks(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /**
     * 契约 §13：查询人工与 AI 审查摘要。
     */
    @GetMapping("/{mergeRequestId}/reviews")
    public ApiResponse<?> reviews(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                  @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        List<MergeRequestReviewResponse> data = mergeRequestService.reviews(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /** 查询 Qgents 创建的 MR 普通评论。行级评论仍通过 Diff 评论接口查询。 */
    @GetMapping("/{mergeRequestId}/comments")
    public ApiResponse<?> comments(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                   @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(commentService.list(projectId, mergeRequestId, userId), request);
    }

    /** 在真实 GitHub MR 对应的 Issue 讨论中创建普通评论。 */
    @PostMapping("/{mergeRequestId}/comments")
    public ApiResponse<?> comment(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                  @AuthenticationPrincipal UUID userId,
                                  @Valid @RequestBody MergeRequestCommentRequest body,
                                  HttpServletRequest request) {
        return ok(commentService.add(projectId, mergeRequestId, userId, body), request);
    }

    /**
     * 契约 §13：触发从 GitHub 同步 MR 最新状态。
     */
    @PostMapping("/{mergeRequestId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> sync(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                               @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.sync(projectId, mergeRequestId, userId);
        return ok(data, request);
    }

    /**
     * 契约 §13：提交 CQ+1 审查（审查者须非 MR 作者）。
     */
    @PostMapping("/{mergeRequestId}/cq-approvals")
    public ApiResponse<?> cqApproval(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                     @AuthenticationPrincipal UUID userId, @RequestBody(required = false) CqDecisionRequest body,
                                     HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.cqApproval(projectId, mergeRequestId, userId,
                body == null ? null : body.getReason());
        return ok(data, request);
    }

    /**
     * 契约 §13：拒绝 CQ 并给出修改意见（审查者须非 MR 作者）。
     */
    @PostMapping("/{mergeRequestId}/cq-rejections")
    public ApiResponse<?> cqRejection(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                      @AuthenticationPrincipal UUID userId, @Valid @RequestBody CqDecisionRequest body,
                                      HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.cqRejection(projectId, mergeRequestId, userId,
                body.getReason());
        return ok(data, request);
    }

    /**
     * 契约 §13：通过质量门禁后执行合并（Project Admin）。
     */
    @PostMapping("/{mergeRequestId}/merge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> merge(@PathVariable UUID projectId, @PathVariable UUID mergeRequestId,
                                @AuthenticationPrincipal UUID userId,
                                @RequestBody(required = false) MergeRequestMergeRequest body,
                                HttpServletRequest request) {
        MergeRequestSummaryResponse data = mergeRequestService.merge(projectId, mergeRequestId, userId,
                body == null ? null : body.getCommitMessage());
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
