package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.service.ContextService;

import java.util.UUID;

/**
 * 群聊上下文接口
 * 只读端点：将需求群消息、需求、关联仓库、已发布 Skill 与已批准 Memory 组装为 Agent 输入上下文。
 */
@RestController
@RequestMapping("/api/v1")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    /**
     * 契约 §7：获取需求群的 Agent 输入上下文（需求+近期消息+仓库+Skill+Memory）。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/context")
    public ApiResponse<?> getContext(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                     @PathVariable UUID groupId, @RequestParam(value = "limit", required = false) Integer limit,
                                     HttpServletRequest request) {
        return ApiResponse.ok(contextService.buildForGroup(userId, projectId, groupId, limit),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 §7：按关键字与标签检索项目上下文（返回匹配的已发布 Skill、已批准 Memory 与可选群消息）。
     */
    @GetMapping("/projects/{projectId}/context/search")
    public ApiResponse<?> search(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                 @RequestParam(value = "q", required = false) String q,
                                 @RequestParam(value = "tag", required = false) String tag,
                                 @RequestParam(value = "groupId", required = false) UUID groupId,
                                 @RequestParam(value = "limit", required = false) Integer limit,
                                 HttpServletRequest request) {
        return ApiResponse.ok(contextService.search(userId, projectId, q, tag, groupId, limit),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
