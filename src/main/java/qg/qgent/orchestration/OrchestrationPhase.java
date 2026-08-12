package qg.qgent.orchestration;

/**
 * Agent 执行相位。Orchestrator 按相位推进 Task 生命周期：
 * PLAN → CODING → TESTING → REVIEWING → SUCCESS/FAILED/CANCELLED。
 * 质量或基础设施失败时按状态机回到 CODING 或同相位重试。
 */
public enum OrchestrationPhase {
    /** 需求理解与计划产出阶段（方案 B：不创建 TaskRun）。 */
    PLAN,
    /** 按计划修改 Workspace 代码。 */
    CODING,
    /** 执行测试并判定是否满足验收。 */
    TESTING,
    /** 审查代码质量、安全与契约一致性。 */
    REVIEWING;

    /**
     * 相位对应的 TaskStep 角色。PLAN 阶段没有可挂的 TaskStep（产出步骤），返回 null。
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
