package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Parameter;
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
     * 契约 v1.8.0 §22.4（前端联调）：获取单张 Agent 卡。
     * <p>
     * projectId 为可选 query：传了仅做两项校验——① 项目属于该 Agent 的团队（agents.team_id == projects.team_id）；
     * ② 当前用户对该项目有访问权（项目成员或 Team Owner 兜底）。校验失败返回 404（资源不可见），
     * 非法 UUID 返回 400，不返回 500。
     */
    @GetMapping("/teams/{teamId}/agents/{agentId}")
    public ApiResponse<AgentResponse> get(@PathVariable UUID teamId, @PathVariable UUID agentId,
                                          @Parameter(description = "可选：项目 ID，仅校验「项目属于该团队 + 当前用户有项目访问权」") @RequestParam(required = false) UUID projectId,
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
