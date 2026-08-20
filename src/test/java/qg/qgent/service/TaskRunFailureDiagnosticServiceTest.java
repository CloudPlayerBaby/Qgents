package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskRunFailureDiagnosticEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskRunFailureDiagnosticMapper;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskRunFailureDiagnosticServiceTest {

    @Test
    void persistsUnknownInfrastructureCodeWithRedactedBoundedDetail() {
        TaskRunFailureDiagnosticMapper mapper = mock(TaskRunFailureDiagnosticMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        TaskRunFailureDiagnosticService service = new TaskRunFailureDiagnosticService(mapper);
        Fixture fixture = new Fixture();
        AgentRunOutcome outcome = infrastructureFailure("ANDROID_SDK_PATH_MISSING",
                "AGENT_EXECUTION", "IllegalStateException",
                "JAVA_HOME=/opt/jdk Bearer secret-token endpoint=https://worker.internal/run C:\\worker\\secret");

        service.record(fixture.task, fixture.run, fixture.step, OrchestrationPhase.TESTING, outcome);

        ArgumentCaptor<TaskRunFailureDiagnosticEntity> stored =
                ArgumentCaptor.forClass(TaskRunFailureDiagnosticEntity.class);
        verify(mapper).insert(stored.capture());
        TaskRunFailureDiagnosticEntity value = stored.getValue();
        assertThat(value.getProjectId()).isEqualTo(fixture.task.getProjectId());
        assertThat(value.getTaskId()).isEqualTo(fixture.task.getId());
        assertThat(value.getTaskRunId()).isEqualTo(fixture.run.getId());
        assertThat(value.getTaskStepId()).isEqualTo(fixture.step.getId());
        assertThat(value.getFailureCode()).isEqualTo("ANDROID_SDK_PATH_MISSING");
        assertThat(value.getPublicFailureCode()).isEqualTo("FAILED_INFRASTRUCTURE");
        assertThat(value.getFailureDetail()).contains("[environment omitted]", "[endpoint omitted]", "[host path omitted]");
        assertThat(value.getFailureDetail()).doesNotContain("secret-token", "worker.internal", "C:\\worker");
        assertThat(value.getFailureDetail()).hasSizeLessThanOrEqualTo(4_097);
        assertThat(value.getDetailFingerprint()).hasSize(64);
    }

    @Test
    void returnsExistingDiagnosticInsteadOfCreatingASecondRecord() {
        TaskRunFailureDiagnosticMapper mapper = mock(TaskRunFailureDiagnosticMapper.class);
        Fixture fixture = new Fixture();
        TaskRunFailureDiagnosticEntity existing = new TaskRunFailureDiagnosticEntity();
        existing.setId(UUID.randomUUID());
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        TaskRunFailureDiagnosticService service = new TaskRunFailureDiagnosticService(mapper);

        TaskRunFailureDiagnosticEntity actual = service.record(fixture.task, fixture.run, fixture.step,
                OrchestrationPhase.CODING, infrastructureFailure("UNKNOWN", null, null, "detail"));

        assertThat(actual).isSameAs(existing);
        verify(mapper, never()).insert(any(TaskRunFailureDiagnosticEntity.class));
    }

    @Test
    void rejectsCrossTaskAssociation() {
        TaskRunFailureDiagnosticMapper mapper = mock(TaskRunFailureDiagnosticMapper.class);
        TaskRunFailureDiagnosticService service = new TaskRunFailureDiagnosticService(mapper);
        Fixture fixture = new Fixture();
        fixture.run.setTaskId(UUID.randomUUID());

        assertThatThrownBy(() -> service.record(fixture.task, fixture.run, fixture.step, OrchestrationPhase.CODING,
                infrastructureFailure("UNKNOWN", null, null, "detail")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    private static AgentRunOutcome infrastructureFailure(String code, String source, String exceptionType,
                                                         String detail) {
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        outcome.setFailureCode(code);
        outcome.setDiagnosticFailureCode(code);
        outcome.setDiagnosticSource(source);
        outcome.setDiagnosticExceptionType(exceptionType);
        outcome.setDiagnosticDetail(detail);
        return outcome;
    }

    private static final class Fixture {
        private final TaskEntity task = new TaskEntity();
        private final TaskRunEntity run = new TaskRunEntity();
        private final TaskStepEntity step = new TaskStepEntity();

        private Fixture() {
            task.setId(UUID.randomUUID());
            task.setProjectId(UUID.randomUUID());
            run.setId(UUID.randomUUID());
            run.setProjectId(task.getProjectId());
            run.setTaskId(task.getId());
            step.setId(UUID.randomUUID());
            step.setTaskId(task.getId());
            run.setTaskStepId(step.getId());
        }
    }
}
