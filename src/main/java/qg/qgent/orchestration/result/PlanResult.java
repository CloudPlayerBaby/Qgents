package qg.qgent.orchestration.result;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plan Agent 的结构化产出：任务理解、目标、实现步骤、测试计划与风险。
 * 由 Orchestrator 转换为 TaskStep 后持久化。
 */
@Data
public class PlanResult {
    /**
     * 任务理解。
     */
    private String taskUnderstanding;
    /**
     * 修改目标列表。
     */
    private List<String> objectives = new ArrayList<>();
    /**
     * 实现步骤。
     */
    private List<ImplementationStep> implementationSteps = new ArrayList<>();
    /**
     * 测试计划。
     */
    private String testPlan;
    /**
     * 验证方式：AUTOMATED 需要执行测试命令，MANUAL 由产物和测试计划完成审查验收。
     */
    private String verificationMode;
    /**
     * 风险列表。
     */
    private List<String> risks = new ArrayList<>();
    /**
     * 交付模式判定：DIFF_FIRST 或 MR_FIRST；可为空（未判定时由硬规则兜底）。
     */
    private String deliveryMode;
    /**
     * 交付模式判定理由（规模/跨仓库/门禁等）；可为空。
     */
    private String scaleReason;
    /**
     * 结构化验证命令（可选）：Planner 明确给出的按仓库验证命令，供 TESTING 阶段优先消费。
     * 命令必须命中 {@code TestCommandResolver} 的白名单模板（mvn/gradle/npm test 或
     * node &lt;tests/*.test.js&gt;），解析器校验不通过的命令会被丢弃并回退自动检测；
     * 缺失或为空时 Test Agent 依据文件树自动解析命令。
     */
    private Verification verification;

    /**
     * 结构化验证命令集合：每个仓库一条命令；按仓库解析执行。
     */
    @Data
    public static class Verification {
        private List<VerificationCommand> commands = new ArrayList<>();
    }

    /**
     * 单个仓库的验证命令。
     */
    @Data
    public static class VerificationCommand {
        /**
         * 目标仓库目录（Workspace 相对路径，与 worktree workspacePath 一致）；
         * 空或 null 表示 Workspace 根目录（单仓库场景）。
         */
        private String repositoryPath;
        /**
         * 白名单验证命令，如 ["node", "tests/todo.test.js"] 或 ["mvn", "test"]；
         * 空列表视为无效条目。
         */
        private List<String> command = new ArrayList<>();
    }

    /**
     * 单个实现步骤。
     */
    @Data
    public static class ImplementationStep {
        private String title;
        private List<String> files = new ArrayList<>();
        private String description;
        /** 步骤执行语义：MUTATE 或 VERIFY。 */
        private String executionMode;
        /**
         * 完成此原子实现步骤所需的能力标签。
         */
        private List<String> requiredCapabilities = new ArrayList<>();
        /**
         * Plan 建议的候选 Agent id（须来自规划时注入的团队候选池清单）；可为 null，
         * 为 null 时由调度器（{@code AgentDispatcher}）自动选择。仅作为选人先验：
         * 物化时仍会经候选池校验，池外/非法 id 一律不采信，不绕过既有安全网。
         */
        private UUID suggestedAgentId;
        /**
         * 该步骤完成后的自然语言验收标准（一句话），把模糊预期显式化供 Coding 对齐与
         * Review 判断；可为 null。Plan 仅是用户需求的解读，实际裁决以用户需求为准。
         */
        private String acceptanceNotes;
        /**
         * 该步骤可选的结构化预期断言（机器可校验信号）。仅当需求足够具体、可量化时由
         * Plan 输出；模糊/开放式需求不输出。断言不剥夺 Review 的语义裁决权——Coding
         * 因合理原因偏离时由后续 Test 作为信号、Review 作为最终判断。可为空列表。
         */
        private List<Assertion> machineAssertions = new ArrayList<>();
    }

    /**
     * 单个结构化预期断言：机器可校验的“计划预期信号”，而非最终裁决。
     * <p>
     * type 取值（白名单见 {@code PlanResultParser}）：EXISTS 目标文件存在；EMPTY 内容为空；
     * LINES_EQ/LINES_GT/LINES_LT 按 \n 统计的行数与 value 比较；CONTAINS/NOT_CONTAINS 内容
     * 包含/不包含 value 子串；ENDS_WITH_NEWLINE 内容是否以换行符结尾（value 为 true/false）。
     * file 为 Workspace 相对路径（多仓库需带 workspacePath 前缀）。
     */
    @Data
    public static class Assertion {
        /** 断言类型：EXISTS/EMPTY/LINES_EQ/LINES_GT/LINES_LT/CONTAINS/NOT_CONTAINS/ENDS_WITH_NEWLINE。 */
        private String type;
        /** 断言目标文件（Workspace 相对路径）。 */
        private String file;
        /** 断言参数：LINES_* 为整数行数，CONTAINS/NOT_CONTAINS 为子串；EXISTS/EMPTY 可为 null；ENDS_WITH_NEWLINE 为 true/false。 */
        private String value;
    }
}
