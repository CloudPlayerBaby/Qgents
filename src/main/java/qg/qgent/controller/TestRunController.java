package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.TestRunService;

import java.util.UUID;

/**
 * 受控 Test Run 与 Dry Run 接口
 * 受控 Test Run 与 Dry Run 的创建与结果查询。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TestRunController {
    private final TestRunService testRunService;

    public TestRunController(TestRunService testRunService) {
        this.testRunService = testRunService;
    }

    /**
     * 契约 §12.4：对指定提交或 Task 发起已启用 Testset 的受控运行。
     */
    @PostMapping("/test-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> createTestRun(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId,
                                        @Valid @RequestBody TestRunCreateRequest body, HttpServletRequest request) {
        TestRunResponse data = testRunService.createTestRun(projectId, userId, body);
        return ok(data, request);
    }

    /**
     * 契约 §12.4：获取测试运行状态、用例摘要和产物引用。
     */
    @GetMapping("/test-runs/{testRunId}")
    public ApiResponse<?> testRun(@PathVariable UUID projectId, @PathVariable UUID testRunId,
                                  @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        TestRunResponse data = testRunService.testRun(projectId, testRunId, userId);
        return ok(data, request);
    }

    /**
     * 契约 §12.4：针对源分支和目标分支发起合并前试运行。
     */
    @PostMapping("/dry-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> createDryRun(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId,
                                       @Valid @RequestBody DryRunCreateRequest body, HttpServletRequest request) {
        DryRunResponse data = testRunService.createDryRun(projectId, userId, body);
        return ok(data, request);
    }

    /**
     * 契约 §12.4：获取试运行报告和冲突、测试摘要。
     */
    @GetMapping("/dry-runs/{dryRunId}/report")
    public ApiResponse<?> dryRunReport(@PathVariable UUID projectId, @PathVariable UUID dryRunId,
                                       @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        DryRunReportResponse data = testRunService.dryRunReport(projectId, dryRunId, userId);
        return ok(data, request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
