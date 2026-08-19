package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.DiffService;

import java.util.UUID;

/**
 * Diff 与审查意见接口
 * 提供项目 Diff 列表、详情、文件与审查意见的读取和添加，以及接受/拒绝 Diff 的受控交付操作。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/diffs")
public class DiffController {
    private final DiffService service;

    public DiffController(DiffService service) {
        this.service = service;
    }

    /**
     * 契约 §12.3：查询项目级 Diff 列表（支持 taskId 过滤与游标分页）。
     */
    @GetMapping
    public ApiPageResponse<DiffListItemResponse> list(@PathVariable UUID projectId,
                                                      @RequestParam(required = false) UUID taskId, @AuthenticationPrincipal UUID actor,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return service.list(projectId, taskId, actor, cursor, limit, id(request));
    }

    /**
     * 契约 §12.3：查询 Diff 变更统计与关联运行摘要。
     */
    @GetMapping("/{diffId}")
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID diffId,
                              @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.get(projectId, diffId, actor), request);
    }

    /**
     * 群聊 Diff 卡按需展开最终 Diff 预览；普通或中间 Diff 不可通过此接口展开。
     */
    @GetMapping("/{diffId}/preview")
    public ApiResponse<?> preview(@PathVariable UUID projectId, @PathVariable UUID diffId,
                                  @RequestParam(required = false) UUID fileId,
                                  @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.finalPreview(projectId, diffId, fileId, actor), request);
    }

    /**
     * 契约 §12.3：游标读取 Diff 文件、hunk 与二进制摘要。
     */
    @GetMapping("/{diffId}/files")
    public ApiPageResponse<DiffFileResponse> files(@PathVariable UUID projectId,
                                                   @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        return service.files(projectId, diffId, actor, cursor, limit, id(request));
    }

    /**
     * 契约 §12.3：查询 Diff 审查意见。
     */
    @GetMapping("/{diffId}/comments")
    public ApiResponse<?> comments(@PathVariable UUID projectId,
                                   @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        return ok(service.comments(projectId, diffId, actor), request);
    }

    /**
     * 契约 §12.3：添加 Diff 审查意见。
     */
    @PostMapping("/{diffId}/comments")
    public ApiResponse<?> comment(@PathVariable UUID projectId,
                                  @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                                  @Valid @RequestBody DiffCommentRequest body,
                                  HttpServletRequest request) {
        return ok(service.addComment(projectId, diffId, actor, body), request);
    }

    /**
     * 契约 §12.3：接受 Diff（受控 Git 基于被审查快照创建提交）。
     */
    @PostMapping("/{diffId}/accept")
    public ApiResponse<?> accept(@PathVariable UUID projectId,
                                 @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                                 @RequestBody(required = false) DiffDecisionRequest body,
                                 HttpServletRequest request) {
        return ok(service.decide(projectId, diffId, actor, true, body == null ? null : body.getReason()),
                request);
    }

    /**
     * 契约 §12.3：拒绝 Diff 并给出退回原因。
     */
    @PostMapping("/{diffId}/reject")
    public ApiResponse<?> reject(@PathVariable UUID projectId,
                                 @PathVariable UUID diffId, @AuthenticationPrincipal UUID actor,
                                 @Valid @RequestBody DiffDecisionRequest body,
                                 HttpServletRequest request) {
        return ok(service.decide(projectId, diffId, actor, false, body.getReason()), request);
    }

    private ApiResponse<?> ok(Object value, HttpServletRequest request) {
        return ApiResponse.ok(value, id(request));
    }

    private String id(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
