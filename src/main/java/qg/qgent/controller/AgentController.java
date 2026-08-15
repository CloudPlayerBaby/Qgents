package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AgentAssignmentListItem;
import qg.qgent.dto.AgentResponse;
import qg.qgent.dto.AgentRuntimeSummary;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.AgentService;

import java.util.List;
import java.util.UUID;

/**
 * 团队 Agent 接口
 * 查询指定 Team 下的 Agent 列表资源，供前端展示团队内可用的 Agent。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private final AgentService service;
    private final CurrentActorProvider currentActor;

    public AgentController(AgentService service, CurrentActorProvider currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    /**
     * 契约 §11.1：查询指定 Team 下的 Agent 列表。
     */
    @GetMapping("/teams/{teamId}/agents")
    public ApiResponse<List<AgentResponse>> list(@PathVariable UUID teamId, HttpServletRequest request) {
        return ApiResponse.ok(service.list(currentActor.currentUserId(), teamId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 v1.8.0 §22（前端联调）：获取单张 Agent 卡；projectId 可选，
     * 传了则校验该 Agent 属于此项目的 Team 且调用者为项目成员。
     */
    @GetMapping("/teams/{teamId}/agents/{agentId}")
    public ApiResponse<AgentResponse> get(@PathVariable UUID teamId, @PathVariable UUID agentId,
                                          @RequestParam(required = false) UUID projectId,
                                          HttpServletRequest request) {
        return ApiResponse.ok(service.get(teamId, agentId, currentActor.currentUserId(), projectId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 v1.8.0 §20（成员 B B04）：Agent 分配列表（需求群/工作流）。
     */
    @GetMapping("/projects/{projectId}/agents/{agentId}/assignments")
    public PagedApiResponse<AgentAssignmentListItem> assignments(@PathVariable UUID projectId,
                                                                 @PathVariable UUID agentId,
                                                                 @AuthenticationPrincipal UUID userId,
                                                                 @RequestParam(required = false) String type,
                                                                 @RequestParam(required = false) String cursor,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 HttpServletRequest request) {
        return service.assignments(projectId, agentId, userId, type, cursor, limit,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 契约 v1.8.0 §20（成员 B B06）：Agent 运行时摘要（runtime/usage/access）。
     */
    @GetMapping("/projects/{projectId}/agents/{agentId}/runtime")
    public ApiResponse<AgentRuntimeSummary> runtime(@PathVariable UUID projectId, @PathVariable UUID agentId,
                                                    @AuthenticationPrincipal UUID userId,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(service.runtime(projectId, agentId, userId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
