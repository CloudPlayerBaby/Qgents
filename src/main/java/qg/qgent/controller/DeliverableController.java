package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DeliverableDecisionRequest;
import qg.qgent.dto.DeliverableResponse;
import qg.qgent.dto.DiffCommentRequest;
import qg.qgent.dto.DiffCommentResponse;
import qg.qgent.dto.DiffFileResponse;
import qg.qgent.dto.DiffResponse;
import qg.qgent.service.DeliverableService;

import java.util.List;
import java.util.UUID;

/**
 * 交付物、Diff 与审查意见端点（12.3）。
 * accept/reject 为同步决策返回 200；添加 Diff 评论为 POST 需 Idempotency-Key。
 * 交付物由受控执行服务产出，客户端不得伪造其提交、测试结果或 Diff。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class DeliverableController {
    private final DeliverableService deliverableService;

    public DeliverableController(DeliverableService deliverableService) {
        this.deliverableService = deliverableService;
    }

    /**
     * 查询工作包产出的交付物（游标分页）。
     */
    @GetMapping("/work-packages/{workPackageId}/deliverables")
    public ApiPageResponse<DeliverableResponse> listByWorkPackage(@PathVariable UUID projectId,
            @PathVariable UUID workPackageId, @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return deliverableService.listByWorkPackage(projectId, workPackageId, userId, cursor, limit,
                requestId(request));
    }

    /** Lists repository-scoped deliverables and prior rejected versions for a task. */
    @GetMapping("/tasks/{taskId}/deliverables")
    public ApiPageResponse<DeliverableResponse> listByTask(@PathVariable UUID projectId,
            @PathVariable UUID taskId, @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        return deliverableService.listByTask(projectId, taskId, userId, cursor, limit, requestId(request));
    }

    /** Returns the overall Task delivery and its repository items. */
    @GetMapping("/tasks/{taskId}/delivery")
    public ApiResponse<?> taskDelivery(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(deliverableService.taskDelivery(projectId, taskId, userId), request);
    }

    /** Accepts the overall Task delivery; repository MRs still pass their own quality gates. */
    @PostMapping("/tasks/{taskId}/delivery/accept")
    public ApiResponse<?> acceptTaskDelivery(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID userId, @RequestBody(required = false) DeliverableDecisionRequest body,
            HttpServletRequest request) {
        return ok(deliverableService.acceptTaskDelivery(projectId, taskId, userId,
                body == null ? null : body.getReason()), request);
    }

    /** Rejects the overall Task delivery and keeps its Task Workspace for revision. */
    @PostMapping("/tasks/{taskId}/delivery/reject")
    public ApiResponse<?> rejectTaskDelivery(@PathVariable UUID projectId, @PathVariable UUID taskId,
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody DeliverableDecisionRequest body,
            HttpServletRequest request) {
        return ok(deliverableService.rejectTaskDelivery(projectId, taskId, userId, body.getReason()), request);
    }

    /**
     * 获取交付物、关联运行、分支和检查摘要。
     */
    @GetMapping("/deliverables/{deliverableId}")
    public ApiResponse<?> detail(@PathVariable UUID projectId, @PathVariable UUID deliverableId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(deliverableService.detail(projectId, deliverableId, userId), request);
    }

    /**
     * 接受通过必要检查的交付物。
     */
    @PostMapping("/deliverables/{deliverableId}/accept")
    public ApiResponse<?> accept(@PathVariable UUID projectId, @PathVariable UUID deliverableId,
            @AuthenticationPrincipal UUID userId, @RequestBody(required = false) DeliverableDecisionRequest body,
            HttpServletRequest request) {
        DeliverableResponse data = deliverableService.accept(projectId, deliverableId, userId,
                body == null ? null : body.getReason());
        return ok(data, request);
    }

    /**
     * 拒绝交付物并给出退回原因。
     */
    @PostMapping("/deliverables/{deliverableId}/reject")
    public ApiResponse<?> reject(@PathVariable UUID projectId, @PathVariable UUID deliverableId,
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody DeliverableDecisionRequest body,
            HttpServletRequest request) {
        DeliverableResponse data = deliverableService.reject(projectId, deliverableId, userId, body.getReason());
        return ok(data, request);
    }

    /**
     * 查询 Diff 的变更统计和关联提交。
     */
    @GetMapping("/diffs/{diffId}")
    public ApiResponse<?> diff(@PathVariable UUID projectId, @PathVariable UUID diffId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        DiffResponse data = deliverableService.diff(projectId, diffId, userId);
        return ok(data, request);
    }

    /**
     * 游标读取 Diff 文件、hunk 和二进制文件摘要。
     */
    @GetMapping("/diffs/{diffId}/files")
    public ApiPageResponse<DiffFileResponse> diffFiles(@PathVariable UUID projectId, @PathVariable UUID diffId,
            @AuthenticationPrincipal UUID userId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return deliverableService.diffFiles(projectId, diffId, userId, cursor, limit, requestId(request));
    }

    /**
     * 查询 Diff 审查意见列表。
     */
    @GetMapping("/diffs/{diffId}/comments")
    public ApiResponse<?> diffComments(@PathVariable UUID projectId, @PathVariable UUID diffId,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        List<DiffCommentResponse> data = deliverableService.diffComments(projectId, diffId, userId);
        return ok(data, request);
    }

    /**
     * 添加一条 Diff 审查意见，绑定当前 Diff 头提交。
     */
    @PostMapping("/diffs/{diffId}/comments")
    public ApiResponse<?> addDiffComment(@PathVariable UUID projectId, @PathVariable UUID diffId,
            @AuthenticationPrincipal UUID userId, @Valid @RequestBody DiffCommentRequest body,
            HttpServletRequest request) {
        DiffCommentResponse data = deliverableService.addDiffComment(projectId, diffId, userId, body.getPath(),
                body.getSide(), body.getLine(), body.getHunkId(), body.getBody());
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
