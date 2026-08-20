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
import static org.mockito.ArgumentMatchers.eq;
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
    void testPromptLimitsNoCodingFixToProvenNonCodeDependencies() {
        String system = new TestPromptBuilder().buildSystem();

        assertThat(system)
                .contains("只有已有明确证据", "Android SDK/JDK/Node", "原因尚不能确定时", "必须为 true")
                .contains("不是对测试是否通过的判断");
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
        assertThat(outcome.getFailureCode()).isEqualTo("PROCESS_EXIT_NONZERO");
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
        assertThat(outcome.getFailureCode()).isEqualTo("PROCESS_EXIT_NONZERO");
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
    void boundsSanitizedModelAndResultCopiesOfExecutionLogs() {
        String stdout = "OUT-HEAD\n" + "o".repeat(80_000) + "\nOUT-TAIL";
        String stderr = "ERR-HEAD\n" + "e".repeat(80_000) + "\nERR-TAIL";
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, stdout, stderr, null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"failed\",\"failures\":[],\"needsCodingFix\":true}");

        AgentRunOutcome outcome = agent().run(input());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(anyString(), captor.capture());
        String modelInput = captor.getValue().get(0).content();
        assertThat(modelInput).hasSizeLessThan(TestPromptBuilder.MAX_STDOUT_CHARS
                        + TestPromptBuilder.MAX_STDERR_CHARS + 5_000)
                .contains("OUT-HEAD", "OUT-TAIL", "ERR-HEAD", "ERR-TAIL", PromptTextLimiter.TRUNCATION_MARKER);
        assertThat(outcome.getTestResult().getStdout()).hasSizeLessThanOrEqualTo(TestPromptBuilder.MAX_STDOUT_CHARS)
                .contains("OUT-HEAD", "OUT-TAIL", PromptTextLimiter.TRUNCATION_MARKER);
        assertThat(outcome.getTestResult().getStderr()).hasSizeLessThanOrEqualTo(TestPromptBuilder.MAX_STDERR_CHARS)
                .contains("ERR-HEAD", "ERR-TAIL", PromptTextLimiter.TRUNCATION_MARKER);
    }

    @Test
    void credentialsAndHostPathsNeverEnterPromptOrTestResult() {
        String stdout = "token=top-secret path C:\\Users\\admin\\project\\pom.xml";
        String stderr = "Authorization: Bearer abc.def and /home/runner/work/output.log";
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, stdout, stderr, null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"failed\",\"failures\":[],\"needsCodingFix\":true}");

        AgentRunOutcome outcome = agent().run(input());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(anyString(), captor.capture());
        String prompt = captor.getValue().get(0).content();
        assertThat(prompt).doesNotContain("top-secret", "C:\\Users\\admin", "abc.def", "/home/runner")
                .contains("[redacted]", "[host path omitted]");
        assertThat(outcome.getTestResult().getStdout()).doesNotContain("top-secret", "C:\\Users\\admin");
        assertThat(outcome.getTestResult().getStderr()).doesNotContain("abc.def", "/home/runner");
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
        assertThat(outcome.getTestResult().getSummary())
                .isEqualTo("测试已执行，但模型未能生成可用的分析结果")
                .doesNotContain("llm down");
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
    void dockerExecutionFailureRetainsStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(false, -1, "", "", "DOCKER_EXEC_FAILED: Docker Exec 执行失败"));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo("DOCKER_EXEC_FAILED");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void nodeTargetDoesNotRunUnrelatedGradleWrapper() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("build.gradle", "gradlew", "hello.js"));
        AgentInput nodeInput = input();
        nodeInput.getCodingResult().setModifiedFiles(List.of("hello.js"));

        AgentRunOutcome outcome = agent().run(nodeInput);

        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
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
    void pureFileTaskSkipsBuildTestEvenWhenBuildToolExists() {
        when(codeAccess.listFiles(any()))
                .thenReturn(List.of("build.gradle", "gradlew", "README.md"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("README.md", "111", "hash"));

        AgentInput fileInput = input();
        fileInput.setRequirement("在 README.md 中写入内容 111");
        fileInput.getCodingResult().setModifiedFiles(List.of("README.md"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().isSuccess()).isTrue();
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
        assertThat(outcome.getTestResult().getCommand()).isEqualTo("file assertions");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void gitkeepTaskSkipsBuildTestAndReadsHiddenTarget() {
        // LocalWorkspaceCodeAccess deliberately omits dot-files from listFiles; verification
        // must still read the trusted CodingResult target directly.
        when(codeAccess.listFiles(any()))
                .thenReturn(List.of("build.gradle", "gradlew"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("repo-1/empty/.gitkeep", "", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

        AgentInput fileInput = input();
        fileInput.setRequirement("创建一个空白文件夹");
        fileInput.getCodingResult().setModifiedFiles(List.of("repo-1/empty/.gitkeep"));
        fileInput.getCodingResult().setModifiedDirectories(List.of("repo-1/empty"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
        assertThat(outcome.getTestResult().getCommand()).isEqualTo("file assertions");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void directoryOnlyChangeDoesNotRunBuildTest() {
        when(codeAccess.listFiles(any()))
                .thenReturn(List.of("build.gradle", "gradlew"));

        AgentInput directoryInput = input();
        directoryInput.setRequirement("创建一个目录");
        directoryInput.getCodingResult().setModifiedFiles(List.of());
        directoryInput.getCodingResult().setModifiedDirectories(List.of("repo-1/empty"));

        AgentRunOutcome outcome = agent().run(directoryInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void nestedHiddenFileIsReadEvenWhenFileListingOmitsIt() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("build.gradle", "gradlew"));
        when(codeAccess.readFile(any(), eq("repo-1/.config/app.yml")))
                .thenReturn(WorkspaceFileReadResult.ok("repo-1/.config/app.yml", "enabled: true", "hash"));

        AgentInput fileInput = input();
        fileInput.setRequirement("修改隐藏配置文件");
        fileInput.getCodingResult().setModifiedFiles(List.of("./repo-1\\.config\\app.yml"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getVerificationMode()).isEqualTo("FILE_ASSERTION");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void pureFileTaskFailsAssertionWhenTargetMissing() {
        when(codeAccess.listFiles(any()))
                .thenReturn(List.of("build.gradle", "gradlew"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("README.md", "111", "hash"));

        AgentInput fileInput = input();
        fileInput.setRequirement("在 README.md 中写入内容 111");
        fileInput.getCodingResult().setModifiedFiles(List.of("README.md"));

        AgentRunOutcome outcome = agent().run(fileInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getTestResult().isSuccess()).isFalse();
        assertThat(outcome.getTestResult().getFailures().get(0).getReason()).contains("不存在");
        verify(executionPort, never()).execute(any(), anyList(), any());
        verify(llm, never()).complete(anyString(), anyList());
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

    @Test
    void environmentFailureIsClassifiedAsInfrastructureWithoutLlmAnalysis() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "", "Connection refused to host mysql:3306", null));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo("TEST_SERVICE_UNAVAILABLE");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void timeoutExitCodeMapsToInfrastructureFailureWithoutLlm() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 124, "partial", "reached the timeout of 10 minutes", null));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo("TEST_EXECUTION_TIMEOUT");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void dependencyResolutionFailureMapsToInfrastructureWithoutLlm() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "",
                        "[ERROR] Could not resolve dependencies for project com.example:app:1.0", null));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo("TEST_DEPENDENCY_UNAVAILABLE");
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void environmentKeywordDoesNotMaskFailureTouchingModifiedFile() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "", "X.java:12 Connection refused", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"connection failed\",\"failures\":[{\"name\":\"x\",\"reason\":\"r\",\"severity\":\"ERROR\"}],\"needsCodingFix\":true}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        verify(llm).complete(anyString(), anyList());
    }

    @Test
    void modifiedBuildFileKeepsDependencyFailureAsQualityFix() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(executionPort.execute(any(), anyList(), any()))
                .thenReturn(new ExecutionResult(true, 1, "", "[ERROR] Could not resolve dependencies", null));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"success\":false,\"summary\":\"deps\",\"failures\":[{\"name\":\"x\",\"reason\":\"r\",\"severity\":\"ERROR\"}],\"needsCodingFix\":true}");
        AgentInput buildInput = input();
        buildInput.getCodingResult().setModifiedFiles(List.of("pom.xml", "src/main/java/X.java"));

        AgentRunOutcome outcome = agent().run(buildInput);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        verify(llm).complete(anyString(), anyList());
    }

    private PlanResult.ImplementationStep stepWithAssertions(String file, String type, String value) {
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("步骤");
        step.setFiles(List.of(file));
        PlanResult.Assertion assertion = new PlanResult.Assertion();
        assertion.setType(type);
        assertion.setFile(file);
        assertion.setValue(value);
        step.setMachineAssertions(List.of(assertion));
        return step;
    }

    @Test
    void machineAssertionSatisfiedIsCapturedWithoutChangingOutcome() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("report.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("report.txt", "a\nb\nc\n", "hash"));

        AgentInput input = input();
        input.setRequirement("向 report.txt 写入三行内容");
        input.getCodingResult().setModifiedFiles(List.of("report.txt"));
        input.getPlanResult().setImplementationSteps(List.of(stepWithAssertions("report.txt", "LINES_EQ", "3")));

        AgentRunOutcome outcome = agent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        TestResult test = outcome.getTestResult();
        assertThat(test.isSuccess()).isTrue();
        assertThat(test.getAssertionResults()).hasSize(1);
        assertThat(test.getAssertionResults().get(0).getType()).isEqualTo("LINES_EQ");
        assertThat(test.getAssertionResults().get(0).getExpected()).isEqualTo("3");
        assertThat(test.getAssertionResults().get(0).getActual()).isEqualTo("3");
        assertThat(test.getAssertionResults().get(0).isPassed()).isTrue();
    }

    @Test
    void assertionMismatchIsSignalNotVerdict() {
        // 计划断言 LINES_EQ=4，实际 5 行：断言未满足，但文件存在且可读，
        // Test 结论仍为通过——偏离是否构成问题由 Review 结合 Coding 偏差声明判断。
        when(codeAccess.listFiles(any())).thenReturn(List.of("report.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("report.txt", "a\nb\nc\nd\ne\n", "hash"));

        AgentInput input = input();
        input.setRequirement("向 report.txt 写入四行内容");
        input.getCodingResult().setModifiedFiles(List.of("report.txt"));
        input.getPlanResult().setImplementationSteps(List.of(stepWithAssertions("report.txt", "LINES_EQ", "4")));

        AgentRunOutcome outcome = agent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        TestResult test = outcome.getTestResult();
        assertThat(test.isSuccess()).isTrue();
        assertThat(test.getAssertionResults()).hasSize(1);
        assertThat(test.getAssertionResults().get(0).isPassed()).isFalse();
        assertThat(test.getAssertionResults().get(0).getActual()).isEqualTo("5");
    }

    @Test
    void assertionsOnFilesNotModifiedByCodingAreSkipped() {
        // 计划预期改 A、Coding 实际改了 B：断言不校验，分歧留给 Review。
        when(codeAccess.listFiles(any())).thenReturn(List.of("report.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("report.txt", "a\nb\nc\n", "hash"));

        AgentInput input = input();
        input.setRequirement("写入 report.txt");
        input.getCodingResult().setModifiedFiles(List.of("report.txt"));
        input.getPlanResult().setImplementationSteps(List.of(stepWithAssertions("other.txt", "LINES_EQ", "3")));

        AgentRunOutcome outcome = agent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getAssertionResults()).isEmpty();
    }

    @Test
    void emptyAssertionReusesEmptyFileDetection() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("note.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("note.txt", "",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

        AgentInput input = input();
        input.setRequirement("创建空文件 note.txt");
        input.getCodingResult().setModifiedFiles(List.of("note.txt"));
        input.getPlanResult().setImplementationSteps(List.of(stepWithAssertions("note.txt", "EMPTY", null)));

        AgentRunOutcome outcome = agent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getAssertionResults()).hasSize(1);
        assertThat(outcome.getTestResult().getAssertionResults().get(0).getType()).isEqualTo("EMPTY");
        assertThat(outcome.getTestResult().getAssertionResults().get(0).isPassed()).isTrue();
    }

    @Test
    void containsAssertionsCheckSubstringPresence() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("config.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("config.txt", "enabled = true\n", "hash"));

        AgentInput input = input();
        input.setRequirement("写入 config.txt");
        input.getCodingResult().setModifiedFiles(List.of("config.txt"));
        input.getPlanResult().setImplementationSteps(List.of(
                stepWithAssertions("config.txt", "CONTAINS", "enabled = true"),
                stepWithAssertions("config.txt", "NOT_CONTAINS", "disabled")));

        AgentRunOutcome outcome = agent().run(input);

        List<TestResult.FileAssertion> results = outcome.getTestResult().getAssertionResults();
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(TestResult.FileAssertion::isPassed);
    }

    @Test
    void noMachineAssertionsLeavesAssertionResultsEmpty() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("report.txt"));
        when(codeAccess.readFile(any(), anyString()))
                .thenReturn(WorkspaceFileReadResult.ok("report.txt", "a\nb\nc\n", "hash"));

        AgentInput input = input();
        input.setRequirement("向 report.txt 写入三行内容");
        input.getCodingResult().setModifiedFiles(List.of("report.txt"));

        AgentRunOutcome outcome = agent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getTestResult().getAssertionResults()).isEmpty();
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
