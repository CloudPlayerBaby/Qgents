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
}
