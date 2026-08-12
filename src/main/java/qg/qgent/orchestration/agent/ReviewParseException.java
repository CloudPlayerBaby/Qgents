package qg.qgent.orchestration.agent;

/**
 * LLM 返回的审查输出非法（非 JSON、缺 success/summary、severity 非法等）时抛出。
 * 由 ReviewAgent 按基础设施失败处理，转为 FAILED_INFRASTRUCTURE 同相位重试，
 * 不得把非法输出当作真实审查结论。
 */
public class ReviewParseException extends RuntimeException {

    public ReviewParseException(String message) {
        super(message);
    }
}
