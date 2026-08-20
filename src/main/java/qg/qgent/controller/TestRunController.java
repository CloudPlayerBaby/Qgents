package qg.qgent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.api.ApiResponse;
import qg.qgent.api.RequestIdFilter;
import qg.qgent.dto.*;
import qg.qgent.service.MrPreflightService;
import qg.qgent.service.PreflightGateService;
import qg.qgent.service.TestRunService;

import java.util.List;
import java.util.UUID;

/**
 * 受控 Test Run 与 Dry Run 接口
 * 受控 Test Run 与 Dry Run 的创建与结果查询。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class TestRunController {
    private final TestRunService testRunService;
    private final PreflightGateService preflightGates;
    private final MrPreflightService preflightService;

    public TestRunController(TestRunService testRunService, PreflightGateService preflightGates,
                             MrPreflightService preflightService) {
        this.testRunService = testRunService;
        this.preflightGates = preflightGates;
        this.preflightService = preflightService;
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
     * 查询项目 Test Run 列表，按创建时间倒序游标分页。
     */
    @GetMapping("/test-runs")
    public ApiPageResponse<TestRunListItemResponse> listTestRuns(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID createdByUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return testRunService.listTestRuns(projectId, userId, repositoryId, taskId, status, createdByUserId,
                cursor, limit, requestId(request));
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
     * 查询项目 Dry Run 列表，按创建时间倒序游标分页；报告详情通过单条报告接口读取。
     */
    @GetMapping("/dry-runs")
    public ApiPageResponse<DryRunListItemResponse> listDryRuns(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetBranch,
            @RequestParam(required = false) UUID createdByUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return testRunService.listDryRuns(projectId, userId, repositoryId, taskId, status, targetBranch,
                createdByUserId, cursor, limit, requestId(request));
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

    /**
     * 仅对瞬时基础设施失败创建新的不可变 Dry Run 尝试。
     */
    @PostMapping("/dry-runs/{dryRunId}/retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<?> retryDryRun(@PathVariable UUID projectId, @PathVariable UUID dryRunId,
                                      @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(testRunService.retryDryRun(projectId, dryRunId, userId), request);
    }

    /**
     * 按 Task 获取全部仓库的分支级 MR 预检状态（多仓库任务逐仓库返回）。
     */
    @GetMapping("/tasks/{taskId}/merge-request-preflight")
    public ApiResponse<?> taskMergeRequestPreflight(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                                    @AuthenticationPrincipal UUID userId,
                                                    HttpServletRequest request) {
        List<MergeRequestPreflightResponse> data = preflightService.getTaskPreflight(projectId, taskId, userId);
        return ok(data, request);
    }

    /**
     * 查询当前 Task 仓库提交创建 MR 前的真实门禁快照。
     */
    @GetMapping("/tasks/{taskId}/repositories/{repositoryId}/preflight")
    public ApiResponse<?> preflight(@PathVariable UUID projectId, @PathVariable UUID taskId,
                                    @PathVariable UUID repositoryId, @RequestParam String targetBranch,
                                    @AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return ok(preflightGates.get(projectId, taskId, repositoryId, targetBranch, userId), request);
    }

    /**
     * 对通过且仍未失效的 Dry Run 提交 MR 前 CQ+1。
     */
    @PostMapping("/dry-runs/{dryRunId}/cq-approvals")
    public ApiResponse<?> preflightCqApproval(@PathVariable UUID projectId, @PathVariable UUID dryRunId,
                                              @AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody(required = false) PreflightCqDecisionRequest body,
                                              HttpServletRequest request) {
        return ok(preflightGates.approve(projectId, dryRunId, userId, body == null ? null : body.getReason()), request);
    }

    /** 拒绝预检 CQ，必须给出修改意见。 */
    @PostMapping("/dry-runs/{dryRunId}/cq-rejections")
    public ApiResponse<?> preflightCqRejection(@PathVariable UUID projectId, @PathVariable UUID dryRunId,
                                               @AuthenticationPrincipal UUID userId,
                                               @Valid @RequestBody PreflightCqDecisionRequest body,
                                               HttpServletRequest request) {
        return ok(preflightGates.reject(projectId, dryRunId, userId, body.getReason()), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
