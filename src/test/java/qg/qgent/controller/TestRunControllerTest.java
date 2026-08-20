package qg.qgent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import qg.qgent.dto.DryRunReportResponse;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DryRunListItemResponse;
import qg.qgent.dto.PageMeta;
import qg.qgent.dto.TestRunListItemResponse;
import qg.qgent.dto.TestRunResponse;
import qg.qgent.service.MrPreflightService;
import qg.qgent.service.PreflightGateService;
import qg.qgent.service.TestRunService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TestRun / Dry Run 控制器契约测试（§12.4）。
 * POST 受理立即返回 202 与运行 ID；GET 详情能返回结构化测试摘要（summary.results / report.tests）。
 */
class TestRunControllerTest {
    private final TestRunService testRunService = mock(TestRunService.class);
    private final PreflightGateService preflightGates = mock(PreflightGateService.class);
    private final MrPreflightService preflightService = mock(MrPreflightService.class);
    private final UUID userId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TestRunController controller = new TestRunController(testRunService, preflightGates, preflightService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalResolver(userId))
                .build();
    }

    @Test
    void createTestRunReturns202WithRunId() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TestRunResponse response = new TestRunResponse(id(runId), id(projectId), id(UUID.randomUUID()),
                "feat/login-api", List.of(id(UUID.randomUUID())), "QUEUED",
                summary("QUEUED", null, List.of()),
                id(userId), "2026-08-15T02:00:00Z",
                "2026-08-15T02:00:00Z", null, "2026-08-15T02:00:00Z");
        when(testRunService.createTestRun(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/projects/{projectId}/test-runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"%s","ref":"feat/login-api","testsetIds":["%s"]}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(id(runId)))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void testRunDetailReturnsStructuredSummaryResults() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID testsetId = UUID.randomUUID();
        TestRunResponse response = new TestRunResponse(id(runId), id(projectId), id(UUID.randomUUID()),
                "feat/login-api", List.of(id(testsetId)), "PASSED",
                summary("PASSED", "0123456789012345678901234567890123456789",
                        List.of(result(id(testsetId), "PASSED", 0, 69, null))),
                id(userId), "2026-08-15T02:00:00Z",
                "2026-08-15T02:00:00Z", "2026-08-15T02:00:05Z", "2026-08-15T02:00:05Z");
        when(testRunService.testRun(any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/{projectId}/test-runs/{runId}", projectId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("PASSED"))
                .andExpect(jsonPath("$.data.summary.results[0].testsetId").value(id(testsetId)))
                .andExpect(jsonPath("$.data.summary.results[0].exitCode").value(0))
                .andExpect(jsonPath("$.data.summary.results[0].durationMs").value(69));
    }

    @Test
    void listEndpointsReturnLightweightCursorPages() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        when(testRunService.listTestRuns(any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ApiPageResponse<>(List.of(new TestRunListItemResponse(
                        id(runId), id(projectId), id(repositoryId), List.of(id(UUID.randomUUID())), null,
                        "feat/login", "RUNNING", id(userId), "2026-08-19T08:00:00Z", null, null)),
                        new PageMeta("next", true), null));
        when(testRunService.listDryRuns(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new ApiPageResponse<>(List.of(new DryRunListItemResponse(
                        id(dryRunId), id(projectId), id(repositoryId), "feat/login", "main", null,
                        "PASSED", id(userId), "2026-08-19T08:00:00Z", "2026-08-19T08:00:01Z",
                        "2026-08-19T08:00:02Z")), new PageMeta(null, false), null));

        mockMvc.perform(get("/api/v1/projects/{projectId}/test-runs", projectId)
                        .param("status", "QUEUED,RUNNING").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.page.nextCursor").value("next"));
        mockMvc.perform(get("/api/v1/projects/{projectId}/dry-runs", projectId)
                        .param("taskId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceRef").value("feat/login"))
                .andExpect(jsonPath("$.data[0].finishedAt").value("2026-08-19T08:00:02Z"));
    }

    @Test
    void createDryRunReturns202WithoutClientTestsetIds() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        DryRunResponse response = new DryRunResponse(id(dryRunId), id(projectId), id(UUID.randomUUID()),
                "0123456789012345678901234567890123456789", "main", "abcdefabcdefabcdefabcdefabcdefabcdefabcd", "QUEUED",
                report("abcdefabcdefabcdefabcdefabcdefabcdefabcd", true, List.of(),
                        Map.of("status", "NOT_REQUIRED", "results", List.of())),
                id(userId), "2026-08-15T02:00:00Z");
        when(testRunService.createDryRun(any(), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/projects/{projectId}/dry-runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"%s","sourceRef":"feat/login-api","targetBranch":"main"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(id(dryRunId)))
                .andExpect(jsonPath("$.data.report.tests.status").value("NOT_REQUIRED"));
    }

    @Test
    void dryRunReportReturnsNestedTestsResults() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        UUID testsetId = UUID.randomUUID();
        DryRunReportResponse response = new DryRunReportResponse(id(dryRunId), "PASSED",
                report("abcdefabcdefabcdefabcdefabcdefabcdefabcd", true, List.of(),
                        summary("PASSED", "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                                List.of(result(id(testsetId), "PASSED", 0, 69, null)))),
                "0123456789012345678901234567890123456789", "main",
                "abcdefabcdefabcdefabcdefabcdefabcdefabcd", 1,
                "2026-08-15T02:00:00Z", "2026-08-15T02:00:00Z");
        when(testRunService.dryRunReport(any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/projects/{projectId}/dry-runs/{dryRunId}/report", projectId, dryRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.tests.status").value("PASSED"))
                .andExpect(jsonPath("$.data.report.tests.results[0].testsetId").value(id(testsetId)))
                .andExpect(jsonPath("$.data.report.tests.results[0].exitCode").value(0))
                .andExpect(jsonPath("$.data.report.tests.results[0].durationMs").value(69));
    }

    private static String id(UUID uuid) {
        return uuid.toString();
    }

    /** 结构化测试摘要：Map.of 不允许 null，用 LinkedHashMap 保留可空字段。 */
    private static Map<String, Object> summary(String status, String resolvedHeadCommit, List<?> results) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("status", status);
        value.put("resolvedHeadCommit", resolvedHeadCommit);
        value.put("results", results);
        return value;
    }

    /** 单条 Testset 结果，failureCode 允许为 null。 */
    private static Map<String, Object> result(String testsetId, String status, int exitCode, long durationMs,
                                              String failureCode) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("testsetId", testsetId);
        value.put("status", status);
        value.put("exitCode", exitCode);
        value.put("durationMs", durationMs);
        value.put("failureCode", failureCode);
        return value;
    }

    /** Dry Run 报告体：targetCommit / mergeable / conflicts / tests。 */
    private static Map<String, Object> report(String targetCommit, boolean mergeable, List<?> conflicts,
                                              Map<String, Object> tests) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("targetCommit", targetCommit);
        value.put("mergeable", mergeable);
        value.put("conflicts", conflicts);
        value.put("tests", tests);
        return value;
    }

    /** standalone MockMvc 下解析 @AuthenticationPrincipal 为固定 userId。 */
    private static final class AuthenticationPrincipalResolver implements HandlerMethodArgumentResolver {
        private final Object principal;

        private AuthenticationPrincipalResolver(Object principal) {
            this.principal = principal;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return principal;
        }
    }
}
