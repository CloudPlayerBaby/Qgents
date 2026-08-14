package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.MemoryCreateRequest;
import qg.qgent.dto.MemoryDraftRequest;
import qg.qgent.dto.MemoryUpdateRequest;
import qg.qgent.dto.RejectRequest;
import qg.qgent.service.MemoryService;

import java.util.UUID;

/**
 * 共享 Memory 接口（§9）。
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 查询 Memory（默认仅 APPROVED，支持状态、标签过滤）。
     */
    @GetMapping("/projects/{projectId}/memories")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "tag", required = false) String tag, HttpServletRequest request) {
        return ok(memoryService.list(userId, projectId, status, tag), request);
    }

    /**
     * 手动创建 Memory 草稿。
     */
    @PostMapping("/projects/{projectId}/memories")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody MemoryCreateRequest body, HttpServletRequest request) {
        return ok(memoryService.create(userId, projectId, body), request);
    }

    /**
     * 根据选中的群聊消息生成 AI 草稿。
     */
    @PostMapping("/projects/{projectId}/memories/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createAiDraft(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody MemoryDraftRequest body, HttpServletRequest request) {
        return ok(memoryService.createAiDraft(userId, projectId, body), request);
    }

    /**
     * 获取 Memory。
     */
    @GetMapping("/projects/{projectId}/memories/{memoryId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, HttpServletRequest request) {
        return ok(memoryService.get(userId, projectId, memoryId), request);
    }

    /**
     * 编辑草稿或审核中内容。
     */
    @PatchMapping("/projects/{projectId}/memories/{memoryId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, @Valid @RequestBody MemoryUpdateRequest body, HttpServletRequest request) {
        return ok(memoryService.update(userId, projectId, memoryId, body), request);
    }

    /**
     * 提交 Memory 审核。
     */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/submit-review")
    public ApiResponse<?> submitReview(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, HttpServletRequest request) {
        return ok(memoryService.submitReview(userId, projectId, memoryId), request);
    }

    /**
     * 批准并发布 Memory（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/approve")
    public ApiResponse<?> approve(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, HttpServletRequest request) {
        return ok(memoryService.approve(userId, projectId, memoryId), request);
    }

    /**
     * 拒绝 Memory 并给出原因（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/reject")
    public ApiResponse<?> reject(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, @Valid @RequestBody RejectRequest body, HttpServletRequest request) {
        return ok(memoryService.reject(userId, projectId, memoryId, body.getReason()), request);
    }

    /**
     * 归档 Memory（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/memories/{memoryId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID memoryId, HttpServletRequest request) {
        return ok(memoryService.archive(userId, projectId, memoryId), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
