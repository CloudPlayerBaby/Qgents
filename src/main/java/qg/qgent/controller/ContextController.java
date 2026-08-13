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
import qg.qgent.service.ContextService;

import java.util.UUID;

/**
 * 群聊上下文接口（点3：聊天上下文管理）。
 * <p>
 * 只读端点：把需求群的历史消息 + 需求 + 关联仓库 + 已发布 Skill + 已批准 Memory 组装为 Agent 输入上下文。
 */
@RestController
@RequestMapping("/api/v1")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    /**
     * 获取需求群的 Agent 输入上下文。
     *
     * @param userId    当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param limit     近期消息条数（默认 50，上限 200）
     * @return 群聊上下文
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/context")
    public ApiResponse<?> getContext(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID groupId, @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        return ApiResponse.ok(contextService.buildForGroup(userId, projectId, groupId, limit),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 按关键字与标签检索项目上下文（点6：相关性检索，不默认注入全部）。
     * <p>
     * 返回匹配的已发布 Skill、已批准 Memory 与可选群消息，专供 Agent 作为 prompt 输入。
     *
     * @param userId    当前用户 ID
     * @param projectId 项目 ID
     * @param q         关键字，可为空
     * @param tag       标签过滤，可为空
     * @param groupId   可选需求群 ID，限定消息检索范围
     * @param limit     消息条数上限（默认 50，上限 200）
     * @return 上下文检索结果
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
