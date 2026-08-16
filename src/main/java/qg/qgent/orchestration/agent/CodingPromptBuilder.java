package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.List;

/**
 * 构造 Coding Agent 的系统提示与初始用户消息：声明工具协议与输出契约，
 * 并把任务上下文、结构化 {@link PlanResult} 与工作区文件树装配进用户消息。
 * 纯文本装配，无状态、不依赖 Spring；不含任何 Secret。
 */
public class CodingPromptBuilder {

    /**
     * 系统提示：DEVELOPER 角色、白名单工具与 JSON 输出契约。
     */
    public String buildSystem() {
        return """
                你是多智能体协作平台中的 DEVELOPER。你会收到一个开发任务、一份实现计划和工作区文件树。请按需读取代码，通过工具真正修改工作区代码，最后输出 finalResult。
                
                可用工具（只能调用以下工具）：
                - list_files：列出工作区所有代码文件，无参数。
                - read_file：读取文件，参数 {"path": "相对路径"}；返回文件内容与当前 sha256。
                - search_code：在代码中检索关键字，参数 {"query": "关键字"}。
                - apply_patch：对已有文本文件精确应用统一 Diff，参数 {"path": "相对路径", "expectedHash": "read_file 返回的 64 位十六进制 sha256", "patch": "统一 Diff 文本"}；expectedHash 必须来自同一次 read_file。
                - write_file：覆盖写入或新建文件，参数 {"path": "相对路径", "content": "文件内容"}；父目录不存在时自动创建。

                工作方式：
                - 先读取与任务相关的文件，理解现状后再修改；只读取需要的文件，不要把整个工作区一次性塞进上下文。
                - 已有文件的修改优先使用 apply_patch 做精确局部修改；只有新建文件或需要整文件替换时才使用 write_file。
                - 每次只输出一个 JSON，不要输出任何多余文本或代码围栏。
                - 需要调用工具时输出：{"toolCall": {"name": "工具名", "arguments": {...}}}
                - 修改完成并确认无误后输出：{"finalResult": {"success": true, "summary": "变更摘要", "modifiedFiles": ["相对路径"], "changes": ["变更说明"]}}
                - 无法完成任务时输出：{"finalResult": {"success": false, "summary": "失败原因", "errors": ["错误说明"]}}

                约束：
                - 只能修改工作区内的文件；路径必须为相对路径，禁止绝对路径、.. 或指向工作区外的路径。
                - apply_patch 的 expectedHash 必须原样取自同一次 read_file 的结果，不得自行计算或伪造。
                - finalResult 的 summary 不得为空。
                """;
    }

    /**
     * 初始用户消息：任务上下文 + 结构化计划 + 工作区文件树。
     */
    public String buildUser(AgentInput input, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题：").append(nullToBlank(input.getTaskTitle()));
        sb.append("\n任务描述：").append(nullToBlank(input.getRequirement()));
        sb.append("\n计划指令：").append(nullToBlank(input.getInstruction()));
        PlanResult plan = input.getPlanResult();
        if (plan != null) {
            appendPlan(sb, plan);
        }
        appendTestResult(sb, input.getTestResult());
        sb.append("\n\n工作区文件树：\n").append(renderTree(files));
        return sb.toString();
    }

    private void appendTestResult(StringBuilder sb, TestResult test) {
        if (test == null) {
            return;
        }
        sb.append("\n\n上次测试结果：").append(test.isSuccess() ? "通过" : "未通过")
                .append("（exit code ").append(test.getExitCode()).append("）");
        if (test.getFailures() != null && !test.getFailures().isEmpty()) {
            sb.append("\n测试失败项：").append(test.getFailures());
        }
        if (test.getSummary() != null && !test.getSummary().isBlank()) {
            sb.append("\n测试摘要：").append(test.getSummary());
        }
    }

    private void appendPlan(StringBuilder sb, PlanResult plan) {
        sb.append("\n\n实现计划：");
        sb.append("\n- 任务理解：").append(nullToBlank(plan.getTaskUnderstanding()));
        if (plan.getObjectives() != null && !plan.getObjectives().isEmpty()) {
            sb.append("\n- 实现目标：").append(String.join("；", plan.getObjectives()));
        }
        if (plan.getImplementationSteps() != null) {
            for (PlanResult.ImplementationStep step : plan.getImplementationSteps()) {
                sb.append("\n  * ").append(nullToBlank(step.getTitle()));
                if (step.getFiles() != null && !step.getFiles().isEmpty()) {
                    sb.append(" 文件：").append(String.join(",", step.getFiles()));
                }
                if (step.getDescription() != null && !step.getDescription().isBlank()) {
                    sb.append("（").append(step.getDescription()).append("）");
                }
            }
        }
        if (plan.getTestPlan() != null && !plan.getTestPlan().isBlank()) {
            sb.append("\n- 测试计划：").append(plan.getTestPlan());
        }
        if (plan.getRisks() != null && !plan.getRisks().isEmpty()) {
            sb.append("\n- 风险：").append(String.join("；", plan.getRisks()));
        }
    }

    private String renderTree(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "(空，未检测到代码文件)";
        }
        return files.stream().map(f -> "- " + f).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
