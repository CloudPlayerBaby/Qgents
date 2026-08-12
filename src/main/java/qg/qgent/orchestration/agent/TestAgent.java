package qg.qgent.orchestration.agent;

import org.springframework.stereotype.Component;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.time.Duration;
import java.util.List;

/**
 * 真实 Test Agent：依据工作区构建工具解析安全测试命令，通过 {@link ExecutionPort} 在
 * Sandbox 内真实执行，收集 exitCode/stdout/stderr，交由 LLM 分析并产出结构化 {@link TestResult}。
 * <p>
 * 真实验证约束：
 * <ul>
 *   <li>success 以 ExecutionPort 返回的真实 exit code 为准（exitCode==0 才通过），LLM 不得推翻；</li>
 *   <li>LLM 不参与命令选择，命令由 {@link TestCommandResolver} 白名单模板解析；</li>
 *   <li>检测不到受支持构建工具时不执行任何命令，判 Task FAILED（不可自动修复）；</li>
 *   <li>ExecutionPort 返回 ok=false（Sandbox 未就绪等）→ FAILED_INFRASTRUCTURE 同相位重试；</li>
 *   <li>LLM 分析失败仅退回基于真实执行的结果，不影响 PASS/FAIL 真实性。</li>
 * </ul>
 * 不修改 Workspace、不 write_file、不调用其他 Agent、不执行 Git 命令、不访问宿主机。
 */
@Component
public class TestAgent implements Agent {

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final ExecutionPort executionPort;
    private final TestCommandResolver commandResolver = new TestCommandResolver();
    private final TestPromptBuilder promptBuilder = new TestPromptBuilder();
    private final TestResultParser parser = new TestResultParser();

    public TestAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, ExecutionPort executionPort) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.executionPort = executionPort;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        try {
            List<String> files = codeAccess.listFiles(input.getWorkspaceId());
            List<String> command = commandResolver.resolve(files);
            if (command == null) {
                return noTestCommand(input);
            }
            ExecutionResult exec = executionPort.execute(input.getWorkspaceId(), command, TEST_TIMEOUT);
            if (!exec.ok()) {
                return infraFailure(input, exec.error() == null ? "test execution unavailable" : exec.error());
            }
            TestResult test = analyze(input, command, exec);
            boolean passed = exec.exitCode() == 0;
            test.setSuccess(passed);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setTestResult(test);
            outcome.setOutcome(passed ? RunOutcome.SUCCEEDED
                    : (test.isNeedsCodingFix() ? RunOutcome.FAILED_QUALITY : RunOutcome.FAILED));
            outcome.setMessage(test.getSummary());
            return outcome;
        } catch (RuntimeException e) {
            return infraFailure(input, e.getMessage());
        }
    }

    /** 由 LLM 分析真实输出；分析失败或非法时退回基于真实执行的结果，不伪造分析。 */
    private TestResult analyze(AgentInput input, List<String> command, ExecutionResult exec) {
        try {
            String raw = llm.complete(promptBuilder.buildSystem(),
                    List.of(LlmMessage.user(promptBuilder.buildUser(input, command, exec))));
            TestResult test = parser.parse(raw);
            test.setExitCode(exec.exitCode());
            test.setCommand(String.join(" ", command));
            test.setStdout(exec.stdout());
            test.setStderr(exec.stderr());
            return test;
        } catch (RuntimeException e) {
            return fallback(exec, command, e.getMessage());
        }
    }

    private TestResult fallback(ExecutionResult exec, List<String> command, String analysisError) {
        TestResult test = new TestResult();
        test.setExitCode(exec.exitCode());
        test.setCommand(String.join(" ", command));
        test.setStdout(exec.stdout());
        test.setStderr(exec.stderr());
        test.setSummary("测试已执行，LLM 分析失败：" + analysisError);
        if (exec.exitCode() != 0) {
            TestResult.Failure failure = new TestResult.Failure();
            failure.setName("test execution");
            failure.setReason("exit code " + exec.exitCode() + "；分析失败无法给出具体失败项");
            failure.setSeverity("ERROR");
            test.setFailures(List.of(failure));
            test.setNeedsCodingFix(true);
        }
        return test;
    }

    private AgentRunOutcome noTestCommand(AgentInput input) {
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(-1);
        test.setSummary("未检测到受支持的项目/测试命令，未执行测试");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("no testable build tool");
        failure.setReason("工作区未检测到 pom.xml / build.gradle / package.json 之一，无法确定安全测试命令");
        failure.setSeverity("ERROR");
        test.setFailures(List.of(failure));

        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setPhase(input.getPhase());
        outcome.setOutcome(RunOutcome.FAILED);
        outcome.setTestResult(test);
        outcome.setMessage(test.getSummary());
        return outcome;
    }

    private AgentRunOutcome infraFailure(AgentInput input, String message) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(input.getPhase());
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setMessage("test agent failed: " + message);
        return failure;
    }
}
