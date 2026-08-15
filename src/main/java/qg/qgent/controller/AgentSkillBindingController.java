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
 * Agent-Skill 绑定接口
 * 管理 Agent 在当前项目内的 Skill 绑定集，全量替换且幂等，无需 Idempotency-Key。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentSkillBindingController {

    private final AgentSkillBindingService bindingService;

    public AgentSkillBindingController(AgentSkillBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 契约 §11.1.1：读取指定 Agent 在当前项目的 Skill 绑定集。 */
    @GetMapping("/projects/{projectId}/agent-skill-bindings/{agentId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID agentId, HttpServletRequest request) {
        return ok(bindingService.get(projectId, agentId, userId), request);
    }

    /**
     * 契约 §11.1.1：全量替换 Skill 绑定集（空数组清空，幂等）。
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
