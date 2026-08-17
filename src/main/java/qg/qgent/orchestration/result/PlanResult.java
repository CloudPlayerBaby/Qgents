package qg.qgent.orchestration.result;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
        /**
         * 完成此原子实现步骤所需的能力标签。
         */
        private List<String> requiredCapabilities = new ArrayList<>();
    }
}
