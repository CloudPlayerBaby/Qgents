package qg.qgent.orchestration.agent;

/**
 * LLM 返回的测试分析输出非法（非 JSON、缺 success/summary 等）时抛出。
 * 由 TestAgent 捕获并退回基于真实 exit code 的结果，不把分析失败当作基础设施失败。
 */
public class TestParseException extends RuntimeException {

    public TestParseException(String message) {
        super(message);
    }
}
