package qg.qgent.orchestration;

import lombok.Data;

/**
 * 状态机循环计数：质量修复循环与基础设施重试。
 * 由 Orchestrator 在一次 orchestrate 会话内持有并随决策被状态机推进；
 * 持久化留待接入真实异步执行后的 Phase 2。
 */
@Data
public class OrchestrationCounters {
    /**
     * 已发生的质量修复循环次数（Test/Review 失败 → 回到 Coding）。
     */
    private int qualityFixLoops = 0;
    /**
     * 已发生的基础设施重试次数（同相位重跑）。
     */
    private int infraRetries = 0;
    /**
     * 质量修复循环上限，超过则 Task FAILED。
     */
    private int maxQualityFixLoops = 3;
    /**
     * 基础设施重试上限，超过则 Task FAILED。
     */
    private int maxInfraRetries = 3;

    /**
     * 是否还能回 Coding 做一次质量修复。
     */
    public boolean canRequeueCoding() {
        return qualityFixLoops < maxQualityFixLoops;
    }

    /**
     * 是否还能同相位基础设施重试。
     */
    public boolean canRetryInfra() {
        return infraRetries < maxInfraRetries;
    }

    public void incrementQualityFixLoops() {
        qualityFixLoops++;
    }

    public void incrementInfraRetries() {
        infraRetries++;
    }
}
