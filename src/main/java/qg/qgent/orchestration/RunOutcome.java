package qg.qgent.orchestration;

/**
 * 单次 Agent Run 的确定性结果分类，供状态机路由。
 * 质量失败与基础设施失败必须区分：前者走质量修复循环，后者走同相位重试。
 */
public enum RunOutcome {
    /**
     * Agent 完成且结果通过。
     */
    SUCCEEDED,
    /**
     * Test/Review 质量失败且可自动修复（needsCodingFix=true），回到 Coding 重试。
     */
    FAILED_QUALITY,
    /**
     * 不可自动修复的质量失败，或 Plan/Coding 自身失败。
     */
    FAILED,
    /**
     * 测试命令已真实执行但非零退出，且非明显代码缺陷（环境/依赖/网络/服务/超时/构建工具不可用），
     * 交由 Review 兜底审查代码逻辑：代码无误则放行（终态如实标注「测试未执行」），代码有误则回 Coding。
     */
    TEST_FAILED,
    /**
     * LLM/Sandbox 等基础设施失败，可同相位重试。
     */
    FAILED_INFRASTRUCTURE,
    /**
     * 用户取消或检测到取消请求。
     */
    CANCELLED
}
