package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.AgentInput;

import java.util.List;
import java.util.Map;

/**
 * 构造 Plan Agent 两轮提示词：第一轮让 LLM 从文件树挑选要读取的文件，
 * 第二轮携带任务上下文、文件树与按需读取的文件内容生成实现计划。
 * <p>
 * 纯文本装配，无状态、不依赖 Spring；不含任何 Secret。
 */
public class PlanPromptBuilder {

    /**
     * 第一轮系统提示：只输出要读取的文件路径 JSON。
     */
    public String buildSelectFilesSystem() {
        return """
                你是一个代码规划助手。你会收到一个开发任务和一个工作区文件树。请判断哪些文件对制定实现计划最有帮助，输出需要读取的文件相对路径。
                
                要求：
                - 只输出 JSON，格式：{"readRequests": ["path1", "path2"]}
                - 最多选择 8 个文件，按重要性排序
                - 优先选择能说明项目结构、入口与契约的文件（如 README、构建清单、主类、路由或接口定义）
                - 不要选择构建产物、二进制文件或 .git 下的文件
                - 不需要读取任何文件时输出 {"readRequests": []}
                """;
    }

    /**
     * 第一轮用户提示：任务 + 文件树。
     */
    public String buildSelectFilesUser(AgentInput input, List<String> files) {
        return "任务标题：%s\n任务描述：%s\n计划指令：%s\n\n工作区文件树：\n%s"
                .formatted(input.getTaskTitle(), input.getRequirement(), input.getInstruction(), renderTree(files));
    }

    /**
     * 第二轮系统提示：PLANNER 角色、输出约束与 JSON 结构。
     */
    public String buildPlanSystem() {
        return """
                你是多智能体协作平台中的 PLANNER。请基于开发任务与工作区代码，制定一份可执行、可被后续 Coding Agent 直接消费的实现计划，并在计划中判定该任务的交付模式。
                
                约束：
                - 你只做规划，绝不修改、创建或删除任何文件，不调用其他 Agent。
                - 只输出 JSON，不要输出任何多余文本或代码围栏。
                - 输出 JSON 必须严格符合以下结构：
                {
                  "taskUnderstanding": "对需求的完整理解",
                  "implementationGoals": ["目标1", "目标2"],
                  "steps": [{"title": "步骤标题", "files": ["相对路径1", "相对路径2"], "description": "该步骤做什么", "requiredCapabilities": ["java", "spring-boot"]}],
                  "testPlan": "如何验证实现符合需求",
                  "risks": ["风险1"],
                  "deliveryMode": "DIFF_FIRST",
                  "scaleReason": "选择该交付模式的理由"
                }
                - taskUnderstanding、implementationGoals、testPlan 不得为空；steps 至少一项、至多 12 项，每项必须有 title 和至少一个 files；risks 可为空数组。
                - 每一项 steps 必须是一次 Coding Agent 调用可以独立完成的原子实现单元，不能重复整项需求。
                - requiredCapabilities 是可选的小写 kebab-case 能力标签数组；只填写该步骤实际需要的专项能力。
                - files 必须是无 .. 的相对路径；只能引用给出的文件树中已有的文件，或明确需要新建的文件（在 description 说明）。
                - 不要臆造文件树中不存在的既有文件。

                交付模式判定规则（deliveryMode 二选一，必须给出且只能给出 DIFF_FIRST 或 MR_FIRST）：
                - MR_FIRST（大功能，直接提 PR 走代码审查）：改动涉及多个仓库；或实现步骤超过 2 个（多模块/跨前后端）；或属于新功能模块/架构级改动；或风险高、需要人逐行审查；或验证复杂度高（需要 Dry Run 验证合并冲突/兼容性）。
                - DIFF_FIRST（小功能，先回 Diff 供用户确认）：补丁式修改（修 bug、加接口、小重构）、单仓库、实现步骤 1~2 个、风险低、现有测试跑一遍即可。
                - scaleReason 必须用一句话说明选择该模式的具体依据（如"涉及前后端 2 个仓库、4 个开发步骤，属跨模块新功能"），不得为空。
                """;
    }

    /**
     * 第二轮用户提示：任务 + 文件树 + 按需读取的文件内容。
     */
    public String buildPlanUser(AgentInput input, List<String> files, Map<String, String> fileContents) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题：%s\n任务描述：%s\n计划指令：%s\n\n工作区文件树：\n%s"
                .formatted(input.getTaskTitle(), input.getRequirement(), input.getInstruction(), renderTree(files)));
        if (!fileContents.isEmpty()) {
            sb.append("\n已读取文件内容（供你分析代码结构）：\n");
            fileContents.forEach((path, content) -> sb.append("--- ").append(path).append(" ---\n").append(content).append('\n'));
        }
        sb.append(ContextPromptRenderer.render(input));
        return sb.toString();
    }

    private String renderTree(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "(空，未检测到代码文件)";
        }
        return files.stream().map(f -> "- " + f).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
