package qg.qgent.orchestration.agent;

import qg.qgent.entity.AgentEntity;
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

    static final int MAX_FILE_TREE_CHARS = 20_000;
    static final int MAX_FILE_CONTENT_CHARS = 16_000;
    static final int MAX_TOTAL_FILE_CONTENT_CHARS = 48_000;

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
     * 第二轮系统提示：PLANNER 角色、输出约束与 JSON 结构（无叠加，供内置兜底调用）。
     */
    public String buildPlanSystem() {
        return buildPlanSystem(null);
    }

    /**
     * 第二轮系统提示：PLANNER 角色、输出约束与 JSON 结构，可按需追加自定义 Agent 补充指引。
     *
     * @param overlayPrompt 自定义 Agent 的补充指引（来自 AgentEntity.prompt）；null/空白表示无叠加。
     */
    public String buildPlanSystem(String overlayPrompt) {
        return """
                你是多智能体协作平台中的 PLANNER。请基于开发任务、工作区代码与「可用 Agent 清单」，制定一份可执行、可被后续 Coding Agent 直接消费的实现计划，并在计划中判定该任务的交付模式。
                
                约束：
                - 你只做规划，绝不修改、创建或删除任何文件，不调用其他 Agent。
                - 只输出 JSON，不要输出任何多余文本或代码围栏。
                - 输出 JSON 必须严格符合以下结构：
                {
                  "taskUnderstanding": "对需求的完整理解",
                  "implementationGoals": ["目标1", "目标2"],
                  "steps": [{"title": "步骤标题", "files": ["相对路径1", "相对路径2"], "description": "该步骤做什么", "executionMode": "MUTATE", "requiredCapabilities": ["java", "spring-boot"], "suggestedAgentId": "可选，团队可用 Agent 的 id", "acceptanceNotes": "该步骤完成后的验收标准，一句话", "machineAssertions": [{"type": "LINES_EQ", "file": "相对路径", "value": "4"}]}],
                  "testPlan": "如何验证实现符合需求",
                  "verificationMode": "AUTOMATED",
                  "verification": {"commands": [{"repositoryPath": "可选，仓库的 workspacePath；单仓库省略", "command": ["mvn", "test"]}]},
                  "risks": ["风险1"],
                  "deliveryMode": "DIFF_FIRST",
                  "scaleReason": "选择该交付模式的理由"
                }
                - taskUnderstanding、implementationGoals、testPlan 不得为空；steps 至少一项、至多 12 项，每项必须有 title 和至少一个 files；risks 可为空数组。
                - verificationMode 必须是 AUTOMATED 或 MANUAL：需要执行构建/测试/检查脚本时使用 AUTOMATED；纯审查、报告核验、文件存在性/内容核验且不需要自动化命令时使用 MANUAL。
                - verification 是可选字段：仅当 verificationMode=AUTOMATED 且你能从文件树确认明确的测试入口时输出；command 只能使用下列白名单模板之一（不输出其他任何命令）：["mvn", "test"]、["sh", "./mvnw", "test"]、["gradle", "test"]、["sh", "./gradlew", "test"]、["npm", "test"]、["node", "tests/某个.test.js"]（node 目标必须是 tests/ 或 test/ 目录下的 *.test.js / *.spec.js / *.test.mjs / *.spec.mjs / *.test.jsx / *.spec.jsx 文件，且该文件必须存在于文件树）。多仓库 Workspace 下每个仓库一条命令，repositoryPath 填该仓库的 workspacePath（必须与文件树中该仓库的目录前缀一致）；无法确认测试入口时省略整个 verification 字段，由测试阶段自动探测。
                - 每一项 steps 必须是一次 Coding Agent 调用可以独立完成的原子实现单元，不能重复整项需求。
                - executionMode 必须是 MUTATE 或 VERIFY：需要创建/修改文件时使用 MUTATE；只检查文件、验证现状或运行只读检查时使用 VERIFY。VERIFY 步骤不得要求 Agent 修改文件。
                - requiredCapabilities 是可选的小写 kebab-case 能力标签数组；只填写该步骤实际需要的专项能力。
                - suggestedAgentId 是可选的建议执行 Agent id：必须来自用户消息中「可用 Agent 清单」列出的 id；每个步骤尽量指派职责匹配的候选 Agent，让不同专长的 Agent 各司其职；无法确定或无需指定时省略该字段。
                - 制定步骤前先审阅「可用 Agent 清单」，主动识别与任务或步骤匹配的候选 Agent。清单中 default=false 表示自定义 Agent；若其名称或说明显示专长与某一步匹配，应优先在该步骤填写其 suggestedAgentId，让后续编排实际调度该 Agent；不要因为已有同角色的默认 Agent 就忽略匹配的自定义 Agent。仅当没有匹配的候选 Agent 时才省略 suggestedAgentId，且不得为凑指派而选择无关 Agent。
                - acceptanceNotes 是可选字段：一句话自然语言说明该步骤完成后如何验收，把模糊预期显式化，供 Coding 对齐与 Review 判断；无法简明描述时省略，不臆造。
                - machineAssertions 是可选字段：仅当需求足够具体、可机器校验时输出（如"文件应为空""行数等于 4""内容包含 xxx"）；type 取值 EXISTS/EMPTY/LINES_EQ/LINES_GT/LINES_LT/CONTAINS/NOT_CONTAINS，file 为相对路径，value 为整数行数（LINES_*）或子串（CONTAINS/NOT_CONTAINS）；模糊/开放式需求（优化、重构、风格、设计调整）不得输出假精确断言。断言是预期信号而非最终裁决：Coding 因合理原因偏离时由后续 Test/Review 判断，不阻断计划。
                - files 必须是无 .. 的相对路径；只能引用给出的文件树中已有的文件，或明确需要新建的文件（在 description 说明）。
                - 多仓库 Workspace 下，files 的每一项必须以对应仓库的 workspacePath 开头，格式为 workspacePath/仓库内路径；新建目录和新建文件同样必须带此前缀。禁止输出 src/App.vue、vue3/、package.json 这类无法确定仓库的裸路径。单仓库时才可使用仓库内相对路径。
                - 不要臆造文件树中不存在的既有文件。

                交付模式判定规则（deliveryMode 二选一，必须给出且只能给出 DIFF_FIRST 或 MR_FIRST）：
                - MR_FIRST（大功能，自动 commit/push 后等待 MR 前预检）：改动涉及多个仓库；或实现步骤超过 2 个（多模块/跨前后端）；或属于新功能模块/架构级改动；或风险高、需要人逐行审查；或验证复杂度高（需要 Dry Run 验证合并冲突/兼容性）。MR 只能在 Dry Run 和独立 CQ+1 通过后由用户显式创建。
                - DIFF_FIRST（小功能，先回 Diff 供用户确认）：补丁式修改（修 bug、加接口、小重构）、单仓库、实现步骤 1~2 个、风险低、现有测试跑一遍即可。
                - scaleReason 必须用一句话说明选择该模式的具体依据（如"涉及前后端 2 个仓库、4 个开发步骤，属跨模块新功能"），不得为空。
                """ + CustomAgentPrompt.overlay(overlayPrompt);
    }

    /**
     * 第二轮用户提示：任务 + 文件树 + 按需读取的文件内容 + 可用 Agent 清单。
     *
     * @param agents 团队候选 Agent 池（ACTIVE + 对任务创建者可见，不限角色），供拆步骤时
     *               考虑如何利用不同专长的 Agent；可为空列表（池不可用时降级为纯业务规划）。
     */
    public String buildPlanUser(AgentInput input, List<String> files, Map<String, String> fileContents,
                                List<AgentEntity> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题：%s\n任务描述：%s\n计划指令：%s\n\n工作区文件树：\n%s"
                .formatted(input.getTaskTitle(), input.getRequirement(), input.getInstruction(), renderTree(files)));
        if (input.getFeedback() != null && !input.getFeedback().isBlank()) {
            sb.append("\n\n上次规划失败反馈：").append(input.getFeedback());
        }
        if (!fileContents.isEmpty()) {
            sb.append("\n已读取文件内容（供你分析代码结构）：\n");
            int remaining = MAX_TOTAL_FILE_CONTENT_CHARS;
            for (Map.Entry<String, String> entry : fileContents.entrySet()) {
                if (remaining <= 0) {
                    break;
                }
                int limit = Math.min(MAX_FILE_CONTENT_CHARS, remaining);
                String modelCopy = PromptTextLimiter.limitHeadTail(entry.getValue(), limit);
                sb.append("--- ").append(entry.getKey()).append(" ---\n").append(modelCopy).append('\n');
                remaining -= modelCopy.length();
            }
        }
        appendAgentPool(sb, agents);
        sb.append(ContextPromptRenderer.render(input));
        return sb.toString();
    }

    /**
     * 追加「可用 Agent 清单」段落：每个 Agent 给出 id / name / role / default / description，
     * 并提醒步骤的 suggestedAgentId 必须取自该清单。
     */
    private void appendAgentPool(StringBuilder sb, List<AgentEntity> agents) {
        if (agents == null || agents.isEmpty()) {
            sb.append("\n可用 Agent 清单：无（将由系统自动分配执行 Agent）\n");
            return;
        }
        sb.append("\n可用 Agent 清单（团队候选，供你拆分步骤时按职责指派）：\n");
        for (AgentEntity agent : agents) {
            sb.append("- id: ").append(agent.getId())
                    .append(", name: ").append(nullToBlank(agent.getName()))
                    .append(", role: ").append(nullToBlank(agent.getRole()))
                    .append(", default: ").append(Boolean.TRUE.equals(agent.getIsDefault()))
                    .append(", description: ").append(nullToBlank(agent.getDescription()))
                    .append('\n');
        }
        sb.append("约束：每步的 suggestedAgentId 必须使用清单中出现的 id；不匹配的 id 会被忽略。\n");
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String renderTree(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "(空，未检测到代码文件)";
        }
        String tree = files.stream().map(f -> "- " + f).reduce((a, b) -> a + "\n" + b).orElse("");
        return PromptTextLimiter.limitHeadTail(tree, MAX_FILE_TREE_CHARS);
    }
}
