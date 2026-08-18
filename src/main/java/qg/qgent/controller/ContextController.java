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
 * 只读端点：将需求群消息、需求、关联仓库、Skill 目录与已批准 Memory 组装为 Agent 输入上下文。
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
     * 契约 §7：按关键字检索指定需求群的历史消息。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages/search")
    public ApiResponse<?> searchChatHistory(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                 @PathVariable UUID groupId, @RequestParam("q") String q,
                                 @RequestParam(value = "limit", required = false) Integer limit,
                                 HttpServletRequest request) {
        return ApiResponse.ok(contextService.searchChatHistory(userId, projectId, groupId, q, limit),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
