package qg.qgent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.ProjectSettings;
import qg.qgent.dto.ProjectSettingsUpdateRequest;
import qg.qgent.service.ProjectSettingsService;

import java.util.UUID;

/**
 * 项目设置接口（成员B 后端接口补充清单 §二：需求群规则开关）。
 * GET 项目成员可读；PATCH 仅 Project Admin（部分更新）。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "项目设置", description = "项目级需求群规则开关配置")
public class ProjectSettingsController {

    private final ProjectSettingsService projectSettings;

    public ProjectSettingsController(ProjectSettingsService projectSettings) {
        this.projectSettings = projectSettings;
    }

    /**
     * 读取项目设置（项目成员）。
     */
    @GetMapping("/projects/{projectId}/settings")
    public ApiResponse<ProjectSettings> get(@PathVariable UUID projectId,
                                            @AuthenticationPrincipal UUID userId,
                                            HttpServletRequest request) {
        return ApiResponse.ok(projectSettings.get(projectId, userId),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 更新项目设置（Project Admin，部分更新：未传字段不覆盖）。
     */
    @PatchMapping("/projects/{projectId}/settings")
    public ApiResponse<ProjectSettings> update(@PathVariable UUID projectId,
                                               @AuthenticationPrincipal UUID userId,
                                               @Valid @RequestBody ProjectSettingsUpdateRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.ok(projectSettings.update(projectId, userId, body),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
