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
import qg.qgent.dto.RejectRequest;
import qg.qgent.dto.SkillCreateRequest;
import qg.qgent.dto.SkillUpdateRequest;
import qg.qgent.service.SkillService;

import java.util.UUID;

/**
 * 共享 Skill 接口（§8）。
 */
@RestController
@RequestMapping("/api/v1")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 查询 Skill（支持状态、标签过滤）。
     */
    @GetMapping("/projects/{projectId}/skills")
    public ApiResponse<?> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "tag", required = false) String tag, HttpServletRequest request) {
        return ok(skillService.list(userId, projectId, status, tag), request);
    }

    /**
     * 创建草稿 Skill。
     */
    @PostMapping("/projects/{projectId}/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody SkillCreateRequest body, HttpServletRequest request) {
        return ok(skillService.create(userId, projectId, body), request);
    }

    /**
     * 获取 Skill。
     */
    @GetMapping("/projects/{projectId}/skills/{skillId}")
    public ApiResponse<?> get(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, HttpServletRequest request) {
        return ok(skillService.get(userId, projectId, skillId), request);
    }

    /**
     * 编辑草稿或审核中内容。
     */
    @PatchMapping("/projects/{projectId}/skills/{skillId}")
    public ApiResponse<?> update(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, @Valid @RequestBody SkillUpdateRequest body, HttpServletRequest request) {
        return ok(skillService.update(userId, projectId, skillId, body), request);
    }

    /**
     * 提交 Skill 审核。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/submit-review")
    public ApiResponse<?> submitReview(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, HttpServletRequest request) {
        return ok(skillService.submitReview(userId, projectId, skillId), request);
    }

    /**
     * 发布 Skill（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/approve")
    public ApiResponse<?> approve(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, HttpServletRequest request) {
        return ok(skillService.approve(userId, projectId, skillId), request);
    }

    /**
     * 拒绝 Skill 并给出原因（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/reject")
    public ApiResponse<?> reject(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, @Valid @RequestBody RejectRequest body, HttpServletRequest request) {
        return ok(skillService.reject(userId, projectId, skillId, body.getReason()), request);
    }

    /**
     * 下线已发布 Skill（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/archive")
    public ApiResponse<?> archive(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID skillId, HttpServletRequest request) {
        return ok(skillService.archive(userId, projectId, skillId), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
