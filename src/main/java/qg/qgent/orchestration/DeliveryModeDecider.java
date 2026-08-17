package qg.qgent.orchestration;

import org.springframework.stereotype.Component;

/**
 * 交付模式硬规则兜底判定器（纯逻辑、无 I/O、不调用 LLM）。
 * <p>
 * 在用户未显式指定且 Planner 未给出可信判定时使用，按顺序命中任一条件即判 MR_FIRST：
 * <ol>
 *   <li>涉及仓库数大于 1（跨仓库改动需要按仓库独立 MR）；</li>
 *   <li>DEVELOPER 步骤数大于 2（多模块/跨前后端，值得走 CQ+1 人工审查）；</li>
 *   <li>目标分支配置了 requiredChecks 门禁（受保护分支必须走质量门禁）。</li>
 * </ol>
 * 否则判 DIFF_FIRST。判定优先级由调用方保证：显式指定 &gt; Planner &gt; 本兜底。
 */
@Component
public class DeliveryModeDecider {

    /**
     * 依据仓库数、开发步骤数与目标分支门禁配置给出兜底交付模式。
     *
     * @param repositoryCount              任务涉及仓库数。
     * @param developerStepCount           PLAN 产出的 DEVELOPER 步骤数。
     * @param targetBranchHasRequiredChecks 目标分支是否配置了 requiredChecks 门禁。
     */
    public String decide(int repositoryCount, int developerStepCount, boolean targetBranchHasRequiredChecks) {
        if (repositoryCount > 1) {
            return DeliveryMode.MR_FIRST;
        }
        if (developerStepCount > 2) {
            return DeliveryMode.MR_FIRST;
        }
        if (targetBranchHasRequiredChecks) {
            return DeliveryMode.MR_FIRST;
        }
        return DeliveryMode.DIFF_FIRST;
    }
}