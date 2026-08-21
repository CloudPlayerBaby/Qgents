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
     * 测试未通过（代码缺陷/环境/超时/未检测到测试命令/文件断言/缺人工验收报告等任何原因）。
     * Test 不自行判定任务失败，统一交 Review 裁决：Review 仅按 BLOCKER/MAJOR 判失败并打回
     * Coding，代码无误则放行（终态如实标注「测试未通过/未执行」，不描述为测试通过）。
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
