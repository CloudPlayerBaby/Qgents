package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.DisabledExecutionPort;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestAgent 纯单元测试：Mock ExecutionPort + Mock LLM，验证安全命令解析、真实执行结果驱动
 * PASS/FAIL、输出回灌、ExecutionPort 异常、LLM 分析失败回退与 TestResult 装配。不依赖真实
 * Sandbox，不执行宿主机命令，不写入任何 API Key。
 */
class TestAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final ExecutionPort executionPort = mock(ExecutionPort.class);
    private final UUID workspaceId = UUID.randomUUID();

    private TestAgent agent() {
        return new TestAgent(llm, codeAccess, executionPort);
    }

    @Test
    void passingTestYieldsSuccessOutcome() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 0, "BUILD SUCCESS", "", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":true,\"summary\":\"all tests passed\",\"failures\":[],\"needsCodingFix\":false}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        TestResult test = outcome.getTestResult();
        assertThat(test.isSuccess()).isTrue();
        assertThat(test.getExitCode()).isZero();
        assertThat(test.getCommand()).isEqualTo("mvn test");
    }

    @Test
    void failingTestYieldsQualityFixOutcome() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "", "2 tests failed", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"2 tests failed\",\"failures\":[{\"name\":\"CalculatorTest\",\"reason\":\"expected 5 but got 4\",\"severity\":\"ERROR\"}],\"needsCodingFix\":true}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        assertThat(outcome.getTestResult().getFailures()).hasSize(1);
        assertThat(outcome.getTestResult().getFailures().get(0).getName()).isEqualTo("CalculatorTest");
    }

    @Test
    void malformedAnalysisIsRepairedOnce() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 0, "BUILD SUCCESS", "", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("测试完成，未发现问题。",
                        "{\"success\":true,\"summary\":\"repaired\",\"failures\":[],\"needsCodingFix\":false}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getSummary()).isEqualTo("repaired");
        verify(llm, org.mockito.Mockito.times(2)).complete(anyString(), anyList());
    }

    @Test
    void nonZeroExitCodeOverridesLlmSuccessClaim() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "", "boom", null));
        // LLM 声称成功且不可修复，但真实 exit code != 0，success 必须以真实执行为准。
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":true,\"summary\":\"all pass\",\"failures\":[],\"needsCodingFix\":false}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
    }

    @Test
    void stdoutAndStderrArePassedToLlm() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "STDOUT-HELLO", "STDERR-WORLD", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"failed\",\"failures\":[{\"name\":\"x\",\"reason\":\"r\",\"severity\":\"ERROR\"}],\"needsCodingFix\":true}");

        agent().run(input());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(anyString(), captor.capture());
        assertThat(captor.getValue()).anySatisfy(msg ->
                assertThat(msg.content()).contains("STDOUT-HELLO", "STDERR-WORLD", "真实 exit code：1"));
    }

    @Test
    void executionPortUnavailableMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any())).thenReturn(ExecutionResult.unavailable());

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void missingCommandMapsToInfrastructureFailureWithoutLlmAnalysis() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 127, "", "gradlew: command not found", null));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("workspace-relative wrapper");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void realDisabledExecutionPortMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        TestAgent disabledAgent = new TestAgent(llm, codeAccess, new DisabledExecutionPort());

        AgentRunOutcome outcome = disabledAgent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void executionPortTimeoutMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(false, -1, "partial out", "partial err",
                        "process timed out after PT10M"));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("timed out");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void llmAnalysisFailureFallsBackToRealExecutionResult() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 2, "out", "err", null));
        when(llm.complete(anyString(), anyList())).thenThrow(new RuntimeException("llm down"));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        assertThat(outcome.getTestResult().getExitCode()).isEqualTo(2);
        assertThat(outcome.getTestResult().getSummary()).contains("LLM 分析失败");
    }

    @Test
    void unsupportedBuildToolExecutesNothingAndFails() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("README.md"));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void manualReviewSucceedsWithoutBuildToolWhenDeveloperProducedReport() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("README.md", "notes.txt"));
        AgentInput manualInput = input();
        manualInput.getPlanResult().setVerificationMode("MANUAL");
        manualInput.getCodingResult().setSummary("检查报告：发现 2 项问题");
        manualInput.getCodingResult().setModifiedFiles(List.of());

        AgentRunOutcome outcome = agent().run(manualInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("MANUAL");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void manualReviewFailsWhenDeveloperReportIsMissing() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("README.md"));
        AgentInput manualInput = input();
        manualInput.getPlanResult().setVerificationMode("MANUAL");
        manualInput.getCodingResult().setSummary(null);
        manualInput.getCodingResult().setModifiedFiles(List.of());

        AgentRunOutcome outcome = agent().run(manualInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().getSummary()).contains("缺少 Developer");
    }

    @Test
    void pureFileTaskUsesDeterministicFileAssertionWhenNoBuildToolExists() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("xiaomi.html"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("xiaomi.html", "", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

        AgentInput fileInput = input();
        fileInput.setRequirement("清空 xiaomi.html");
        fileInput.getCodingResult().setModifiedFiles(List.of("xiaomi.html"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().isSuccess()).isTrue();
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
        assertThat(outcome.getTestResult().getCommand()).isEqualTo("file assertions");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void pureFileTaskFailsAssertionWhenClearOperationLeavesContent() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("xiaomi.html"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("xiaomi.html", "not empty", "hash"));

        AgentInput fileInput = input();
        fileInput.setRequirement("清空 xiaomi.html");
        fileInput.getCodingResult().setModifiedFiles(List.of("xiaomi.html"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        assertThat(outcome.getTestResult().getFailures().get(0).getReason()).contains("为空");
        verify(executionPort, never()).execute(any(), anyList(), any());
    }

    @Test
    void testResultCarriesRealExecutionFields() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("build.gradle"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "STDOUT", "STDERR", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"1 test failed\",\"failures\":[{\"name\":\"FooTest\",\"reason\":\"boom\",\"severity\":\"ERROR\"}],\"needsCodingFix\":true}");

        AgentRunOutcome outcome = agent().run(input());

        TestResult test = outcome.getTestResult();
        assertThat(test.isSuccess()).isFalse();
        assertThat(test.getExitCode()).isEqualTo(1);
        assertThat(test.getCommand()).isEqualTo("gradle test");
        assertThat(test.getStdout()).isEqualTo("STDOUT");
        assertThat(test.getStderr()).isEqualTo("STDERR");
        assertThat(test.getSummary()).isEqualTo("1 test failed");
        assertThat(test.getFailures()).hasSize(1);
        assertThat(test.getFailures().get(0).getName()).isEqualTo("FooTest");
        assertThat(test.isNeedsCodingFix()).isTrue();
    }

    private AgentInput input() {
        AgentInput input = new AgentInput();
        input.setProjectId(UUID.randomUUID());
        input.setTaskId(UUID.randomUUID());
        input.setTaskTitle("sample task");
        input.setRequirement("add feature");
        input.setInstruction("run tests to verify");
        input.setPhase(OrchestrationPhase.TESTING);
        input.setWorkspaceId(workspaceId);
        PlanResult plan = new PlanResult();
        plan.setTestPlan("run the project test suite");
        input.setPlanResult(plan);
        CodingResult coding = new CodingResult();
        coding.setSuccess(true);
        coding.setSummary("implemented feature");
        coding.setModifiedFiles(List.of("src/main/java/X.java"));
        input.setCodingResult(coding);
        return input;
    }
}
