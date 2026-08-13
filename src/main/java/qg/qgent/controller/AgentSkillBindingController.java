package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.AgentSkillBindingsRequest;
import qg.qgent.service.AgentSkillBindingService;

import java.util.UUID;

/**
 * Agent-Skill 绑定接口（PUT 全量替换，幂等，无需 Idempotency-Key）。
 * 授权与状态码规则见 {@link AgentSkillBindingService}：403 越权、404 资源缺失、
 * 409 请求 Skill ID 重复、422 Agent/Skill 状态或归属不满足。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentSkillBindingController {

    private final AgentSkillBindingService bindingService;

    public AgentSkillBindingController(AgentSkillBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 读取指定 Agent 在当前项目的 Skill 绑定集（项目成员可读）。 */
    @GetMapping("/projects/{projectId}/agent-skill-bindings/{agentId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID agentId, HttpServletRequest request) {
        return ok(bindingService.get(projectId, agentId, userId), request);
    }

    /**
     * 全量替换指定 Agent 在当前项目的 Skill 绑定；空数组清空全部绑定。
     * 幂等 PUT：重复提交同一请求体返回一致结果。
     */
    @PutMapping("/projects/{projectId}/agent-skill-bindings/{agentId}")
    public ApiResponse<?> replace(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID agentId, @Valid @RequestBody AgentSkillBindingsRequest body,
            HttpServletRequest request) {
        return ok(bindingService.replace(projectId, agentId, userId, body), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
