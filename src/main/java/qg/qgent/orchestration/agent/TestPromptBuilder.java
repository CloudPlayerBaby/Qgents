package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.ExecutionResult;

import java.util.List;

/**
 * 构造 Test Agent 的分析提示词：把任务上下文、计划测试目标、本次修改（CodingResult）、
 * 实际执行的命令与真实输出（exitCode/stdout/stderr）装配给 LLM，要求其产出结构化 TestResult。
 * 纯文本装配，无状态、不依赖 Spring；不含任何 Secret。
 */
public class TestPromptBuilder {

    static final int MAX_STDOUT_CHARS = 24_000;
    static final int MAX_STDERR_CHARS = 24_000;

    /**
     * 系统提示：TESTER 角色、真实结果约束与 JSON 输出契约。
     */
    public String buildSystem() {
        return """
                你是多智能体协作平台中的 TESTER。你会收到一个测试命令或纯文件断言结果、真实的 exit code 以及 stdout/stderr。请基于真实结果分析测试结果，只输出 JSON，不要输出任何多余文本或代码围栏。
                
                约束：
                - success 必须与真实 exit code 一致：exit code == 0 时 success 为 true，否则为 false。不得声称通过，也不得在真实失败时判定成功。
                - 输出 JSON 严格符合以下结构：
                {
                  "success": true,
                  "summary": "对测试结果的总结（通过或失败原因概述）",
                  "failures": [{"name": "失败用例或位置", "reason": "失败原因", "severity": "ERROR"}],
                  "needsCodingFix": true
                }
                - summary 不得为空；failures 可为空数组；needsCodingFix 表示该失败是否可由 Coding Agent 修复（默认 true）。
                - 当验证方式为 FILE_ASSERTION 时，必须以文件存在性、可读性和明确的内容/大小断言为准，不得因为没有 Maven/Gradle/npm 命令就判定失败。
                """;
    }

    /**
     * 用户消息：任务 + 计划测试目标 + 本次修改 + 真实命令与输出。
     */
    public String buildUser(AgentInput input, List<String> command, ExecutionResult exec) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题：").append(nullToBlank(input.getTaskTitle()));
        sb.append("\n任务描述：").append(nullToBlank(input.getRequirement()));
        sb.append("\n测试指令：").append(nullToBlank(input.getInstruction()));
        PlanResult plan = input.getPlanResult();
        if (plan != null && plan.getTestPlan() != null && !plan.getTestPlan().isBlank()) {
            sb.append("\n计划中的测试计划：").append(plan.getTestPlan());
        }
        CodingResult coding = input.getCodingResult();
        if (coding != null) {
            sb.append("\n本次修改摘要：").append(nullToBlank(coding.getSummary()));
            if (coding.getModifiedFiles() != null && !coding.getModifiedFiles().isEmpty()) {
                sb.append("\n修改文件：").append(String.join(",", coding.getModifiedFiles()));
            }
        }
        sb.append("\n\n实际执行命令：").append(String.join(" ", command));
        sb.append("\n真实 exit code：").append(exec.exitCode());
        sb.append("\n--- stdout ---\n")
                .append(PromptTextLimiter.limitHeadTail(exec.stdout(), MAX_STDOUT_CHARS));
        sb.append("\n--- stderr ---\n")
                .append(PromptTextLimiter.limitHeadTail(exec.stderr(), MAX_STDERR_CHARS));
        sb.append(ContextPromptRenderer.render(input));
        return sb.toString();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
