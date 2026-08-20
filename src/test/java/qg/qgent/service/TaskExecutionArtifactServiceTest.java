package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaskExecutionArtifactServiceTest {

    private final TaskExecutionArtifactService service = new TaskExecutionArtifactService(
            mock(TaskExecutionArtifactMapper.class), mock(TaskMapper.class),
            mock(ProjectAccessService.class), mock(EventService.class));

    @Test
    void redactsEmbeddedCredentialsAndHostPathsAtContentLevel() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("message", "failed at C:\\Users\\admin\\repo\\pom.xml and /home/runner/work/x; "
                + "Bearer abc.def token=top-secret password:guess api-key=key123");

        Map<String, Object> sanitized = service.sanitizeSummary(summary);

        assertThat(String.valueOf(sanitized.get("message")))
                .doesNotContain("C:\\Users\\admin", "/home/runner", "abc.def", "top-secret", "guess", "key123")
                .contains("[host path omitted]", "[redacted]");
    }

    @Test
    void unknownInfrastructureCodeUsesAllowlistedFallbackAndControlledMessage() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", "FAILED_INFRASTRUCTURE");
        summary.put("failureCode", "UNKNOWN_INTERNAL_CODE");
        summary.put("message", "java.io failure at /tmp/secret token=leak");

        Map<String, Object> sanitized = service.sanitizeSummary(summary);

        assertThat(sanitized).containsEntry("failureCode", "FAILED_INFRASTRUCTURE")
                .containsEntry("message", "执行基础设施暂不可用");
    }

    @Test
    void ordinaryFailureReplacesAgentMessageWithControlledDescription() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", "FAILED");
        summary.put("status", "FAILED");
        summary.put("failureCode", "FILE_PATCH_FAILED");
        summary.put("message", "apply_patch 工具调用失败，内部模型上下文不应返回");

        Map<String, Object> sanitized = service.sanitizeSummary(summary);

        assertThat(sanitized).containsEntry("failureCode", "FILE_PATCH_FAILED")
                .containsEntry("message", "补丁无法应用，请重新读取文件后重试");
    }

    @Test
    void processFailureKeepsSanitizedStructuredTestFacts() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", "FAILED_QUALITY");
        summary.put("status", "FAILED");
        summary.put("failureCode", "PROCESS_EXIT_NONZERO");
        summary.put("message", "内部异常原文不应展示");
        summary.put("testFailure", Map.of("exitCode", 1, "failureCount", 1,
                "failures", java.util.List.of(Map.of("name", "CalculatorTest",
                        "reason", "stderr: token=secret at C:\\worker\\repo", "severity", "ERROR"))));

        Map<String, Object> sanitized = service.sanitizeSummary(summary);

        assertThat(sanitized).containsEntry("failureCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("message", "工具进程执行失败");
        @SuppressWarnings("unchecked")
        Map<String, Object> testFailure = (Map<String, Object>) sanitized.get("testFailure");
        assertThat(testFailure).containsEntry("exitCode", 1).containsEntry("failureCount", 1);
        assertThat(String.valueOf(testFailure)).contains("CalculatorTest", "[raw output omitted]")
                .doesNotContain("secret", "C:\\worker", "stderr:");
    }
}
