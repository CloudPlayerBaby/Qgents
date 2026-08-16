package qg.qgent.orchestration.agent;

/**
 * LLM 返回的审查输出非法（非 JSON、缺 success/summary、severity 非法、超循环上限等）时抛出。
 * 由 ReviewAgent 按基础设施失败处理，转为 FAILED_INFRASTRUCTURE 同相位重试，
 * 不得把非法输出当作真实审查结论。
 * <p>
 * 携带稳定的 {@link ProtocolFailureCode}，随异常 message 与脱敏观测落库，使一次失败可定位为
 * 长度截断 / JSON 非法 / 未知工具 / 参数非法 / 上下文超限中的一种。
 */
public class ReviewParseException extends RuntimeException {

    private final ProtocolFailureCode code;

    public ReviewParseException(String message) {
        this(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED, message);
    }

    public ReviewParseException(ProtocolFailureCode code, String message) {
        super((code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code) + ": " + message);
        this.code = code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code;
    }

    public ProtocolFailureCode getCode() {
        return code;
    }
}
