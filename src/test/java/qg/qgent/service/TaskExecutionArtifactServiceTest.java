package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskExecutionArtifactServiceTest {

    private final TaskExecutionArtifactMapper artifacts = mock(TaskExecutionArtifactMapper.class);
    private final TaskExecutionArtifactService service = new TaskExecutionArtifactService(
            artifacts, mock(TaskMapper.class), mock(ProjectAccessService.class), mock(EventService.class));

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

    @Test
    void latestFailedQualityReviewingReturnsNewestArtifactWhenFixableByCoding() {
        TaskExecutionArtifactEntity failedQuality = reviewingArtifact(2, "FAILED_QUALITY", true, true);
        when(artifacts.selectList(any(Wrapper.class))).thenReturn(List.of(failedQuality));

        TaskExecutionArtifactEntity found = service.latestFailedQualityReviewingArtifact(UUID.randomUUID(), null);

        assertThat(found).isSameAs(failedQuality);
    }

    @Test
    void latestFailedQualityReviewingReturnsNullWhenNewestReviewPassed() {
        // 最新审查已通过（SUCCEEDED）时，此前的 FAILED_QUALITY 问题已被处理，不得重新喂给开发。
        when(artifacts.selectList(any(Wrapper.class))).thenReturn(List.of(
                reviewingArtifact(3, "SUCCEEDED", true, true)));

        TaskExecutionArtifactEntity found = service.latestFailedQualityReviewingArtifact(UUID.randomUUID(), null);

        assertThat(found).isNull();
    }

    @Test
    void latestFailedQualityReviewingReturnsNullWhenNotFixableByCoding() {
        when(artifacts.selectList(any(Wrapper.class))).thenReturn(List.of(
                reviewingArtifact(1, "FAILED_QUALITY", true, false)));

        TaskExecutionArtifactEntity found = service.latestFailedQualityReviewingArtifact(UUID.randomUUID(), null);

        assertThat(found).isNull();
    }

    @Test
    void latestFailedQualityReviewingReturnsNullWhenNoReviewArtifact() {
        when(artifacts.selectList(any(Wrapper.class))).thenReturn(List.of());

        TaskExecutionArtifactEntity found = service.latestFailedQualityReviewingArtifact(UUID.randomUUID(), null);

        assertThat(found).isNull();
    }

    @Test
    void latestFailedQualityReviewingHonorsRunFilteredQueryResult() {
        TaskExecutionArtifactEntity runArtifact = reviewingArtifact(1, "FAILED_QUALITY", true, true);
        runArtifact.setTaskRunId(UUID.randomUUID());
        when(artifacts.selectList(any(Wrapper.class))).thenReturn(List.of(runArtifact));

        // 传入 taskRunId 的路径仍尊重查询返回结果；run 过滤条件由 MyBatis-Plus eq(condition,...)
        // 表达，这里只验证方法把 runId 传进查询并正确处理返回。
        TaskExecutionArtifactEntity found =
                service.latestFailedQualityReviewingArtifact(UUID.randomUUID(), runArtifact.getTaskRunId());

        assertThat(found).isSameAs(runArtifact);
    }

    private TaskExecutionArtifactEntity reviewingArtifact(int sequenceNo, String outcome, boolean needsCodingFix,
                                                          boolean withReview) {
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setSequenceNo(sequenceNo);
        artifact.setArtifactType("REVIEWING");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", outcome);
        if (withReview) {
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("success", "SUCCEEDED".equals(outcome));
            review.put("needsCodingFix", needsCodingFix);
            review.put("findings", List.of(Map.of("severity", "MAJOR", "file", "src/A.java", "line", 3,
                    "issue", "接口缺少参数校验", "suggestion", "补充校验")));
            summary.put("review", review);
        }
        artifact.setSummary(summary);
        return artifact;
    }
}
