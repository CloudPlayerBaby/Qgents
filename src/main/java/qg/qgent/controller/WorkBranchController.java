package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.WorkBranchResponse;
import qg.qgent.service.WorkBranchService;

import java.util.UUID;

/**
 * 项目代码页的 Qgents 工作分支读取接口。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/work-branches")
public class WorkBranchController {
    private final WorkBranchService service;

    public WorkBranchController(WorkBranchService service) {
        this.service = service;
    }

    /**
     * 查询项目内可追溯的工作分支，不包含 GitHub 全量远端分支。
     */
    @Operation(summary = "查询项目工作分支")
    @GetMapping
    public ApiPageResponse<WorkBranchResponse> list(
            @org.springframework.web.bind.annotation.PathVariable UUID projectId,
            @AuthenticationPrincipal UUID actor,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID requirementGroupId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return service.list(projectId, actor, repositoryId, requirementGroupId, cursor, limit,
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
