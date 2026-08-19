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
    }
}
