package qg.qgent.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.BranchPolicyDto;
import qg.qgent.dto.QualityGateDto;
import qg.qgent.dto.UpdateBranchPolicyRequest;
import qg.qgent.dto.UpdateQualityGateRequest;
import qg.qgent.security.CurrentActorProvider;
import qg.qgent.service.RepositoryBranchConfigService;

/**
 * 分支策略与质量门禁接口（§6.1）。
 */
@RestController
@RequestMapping("/api/v1")
public class RepositoryBranchConfigController {
    private final RepositoryBranchConfigService service;
    private final CurrentActorProvider currentActor;

    public RepositoryBranchConfigController(RepositoryBranchConfigService service, CurrentActorProvider currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    /**
     * 查询受保护分支策略。
     */
    @GetMapping("/projects/{projectId}/repositories/{repositoryId}/branch-policies/{branch}")
    public ApiResponse<BranchPolicyDto> getBranchPolicy(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable String branch,
            HttpServletRequest request) {
        return ok(service.getBranchPolicy(currentActor.currentUserId(), projectId, repositoryId, branch), request);
    }

    /**
     * 配置受保护分支策略。
     */
    @PutMapping("/projects/{projectId}/repositories/{repositoryId}/branch-policies/{branch}")
    public ApiResponse<BranchPolicyDto> updateBranchPolicy(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable String branch,
            @Valid @RequestBody UpdateBranchPolicyRequest body,
            HttpServletRequest request) {
        return ok(service.updateBranchPolicy(currentActor.currentUserId(), projectId, repositoryId, branch, body), request);
    }

    /**
     * 查询目标分支的门禁。
     */
    @GetMapping("/projects/{projectId}/repositories/{repositoryId}/quality-gates/{branch}")
    public ApiResponse<QualityGateDto> getQualityGate(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable String branch,
            HttpServletRequest request) {
        return ok(service.getQualityGate(currentActor.currentUserId(), projectId, repositoryId, branch), request);
    }

    /**
     * 配置目标分支的门禁。
     */
    @PutMapping("/projects/{projectId}/repositories/{repositoryId}/quality-gates/{branch}")
    public ApiResponse<QualityGateDto> updateQualityGate(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable String branch,
            @Valid @RequestBody UpdateQualityGateRequest body,
            HttpServletRequest request) {
        return ok(service.updateQualityGate(currentActor.currentUserId(), projectId, repositoryId, branch, body), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
