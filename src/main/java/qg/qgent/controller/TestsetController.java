package qg.qgent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.TestsetResponse;
import qg.qgent.service.TestsetService;

import java.util.List;
import java.util.UUID;

/**
 * Testset 配置接口（§10）。
 * <p>
 * 本期提供只读列表（含仓库过滤）；创建/修改/启停/删除由 Testset 管理功能后续提供。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testsets")
@Tag(name = "10 Testset", description = "Testset 配置查询")
public class TestsetController {
    private final TestsetService testsetService;

    public TestsetController(TestsetService testsetService) {
        this.testsetService = testsetService;
    }

    /**
     * 查询项目内 Testset 配置列表，支持按仓库过滤。
     */
    @Operation(summary = "查询 Testset 列表")
    @GetMapping
    public ApiResponse<?> list(@PathVariable UUID projectId, @AuthenticationPrincipal UUID actor,
            @RequestParam(required = false) UUID repositoryId, HttpServletRequest request) {
        List<TestsetResponse> data = testsetService.list(projectId, actor, repositoryId);
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
