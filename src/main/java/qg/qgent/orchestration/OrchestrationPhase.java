package qg.qgent.orchestration;

/**
 * Agent 执行相位。Orchestrator 按相位推进 Task 生命周期：
 * PLAN → CODING → TESTING → REVIEWING → SUCCESS/FAILED/CANCELLED。
 * 质量或基础设施失败时按状态机回到 CODING 或同相位重试。
 */
public enum OrchestrationPhase {
    /**
     * 需求理解与计划产出阶段（P0 起为正式 step：创建 TaskRun、落 PlanResult，
     * 经 backfillPlanSteps 回填 DEVELOPER/TESTER 指令）。
     */
    PLAN,
    /**
     * 按计划修改 Workspace 代码。
     */
    CODING,
    /**
     * 执行测试并判定是否满足验收。
     */
    TESTING,
    /**
     * 审查代码质量、安全与契约一致性。
     */
    REVIEWING;

    /**
     * 相位对应的 TaskStep 角色。PLAN 恒返回 null：P0 起角色映射由
     * {@code TaskOrchestrator.stepPhase}（step.role → 相位）反向承担，执行期直接取 step.role。
     */
    public String role() {
        return switch (this) {
            case CODING -> "DEVELOPER";
            case TESTING -> "TESTER";
            case REVIEWING -> "REVIEWER";
            case PLAN -> null;
        };
    }
}
