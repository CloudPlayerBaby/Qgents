package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.WorkspaceDiffPreviewFileResponse;
import qg.qgent.dto.WorkspaceDiffPreviewFileDetailResponse;
import qg.qgent.dto.WorkspaceDiffPreviewResponse;
import qg.qgent.orchestration.preview.WorkspaceDiffPreviewService;

import java.util.List;
import java.util.UUID;

/**
 * Workspace 实时 Diff Preview 只读查询接口（阶段 E）。
 * <p>
 * 与正式 Diff 严格分离：这里只反映 Coding 写过程中的累积工作树变更预览，永不作为已
 * commit/push/MR。鉴权由 {@link WorkspaceDiffPreviewService} 强制：项目成员 + Task 所属
 * 需求群成员（PROJECT_MAIN 项目总群任务按项目成员可见），跨项目/非需求群成员不可见。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/workspace-diff-preview")
public class WorkspaceDiffPreviewController {

    private final WorkspaceDiffPreviewService service;

    public WorkspaceDiffPreviewController(WorkspaceDiffPreviewService service) {
        this.service = service;
    }

    /**
     * 查询预览详情（元数据 + 受控 patch），revision 缺省返回最新。
     */
    @GetMapping
    public ApiResponse<?> get(@PathVariable UUID projectId, @PathVariable UUID taskId,
                              @RequestParam(required = false) Long revision,
                              @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        WorkspaceDiffPreviewResponse value = service.preview(projectId, taskId, actor, revision);
        return ApiResponse.ok(value, id(request));
    }

    /**
     * 查询预览结构化文件列表，revision 缺省返回最新。
     */
    @GetMapping("/files")
    public ApiResponse<?> files(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                @RequestParam(required = false) Long revision,
                                @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        List<WorkspaceDiffPreviewFileResponse> value = service.files(projectId, taskId, actor, revision);
        return ApiResponse.ok(value, id(request));
    }

    /**
     * 查询指定 revision 中单个仓库文件的 patch。
     */
    @GetMapping("/file")
    public ApiResponse<?> file(@PathVariable UUID projectId, @PathVariable UUID taskId,
                               @RequestParam(required = false) Long revision,
                               @RequestParam UUID repositoryId, @RequestParam String path,
                               @AuthenticationPrincipal UUID actor, HttpServletRequest request) {
        WorkspaceDiffPreviewFileDetailResponse value = service.file(projectId, taskId, actor, revision,
                repositoryId, path);
        return ApiResponse.ok(value, id(request));
    }

    private String id(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
