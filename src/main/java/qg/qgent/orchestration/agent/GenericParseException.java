package qg.qgent.orchestration.agent;

/**
 * 自定义 Agent 输出非法（非 JSON、缺必填字段、超循环上限、输出被截断等）时抛出。
 * 由 {@link GenericCustomAgent} 捕获并转为 FAILED_INFRASTRUCTURE，使状态机进入同相位重试。
 * 携带稳定的 {@link ProtocolFailureCode}（复用 Coding/Review 协议错误码），随异常 message
 * 与脱敏观测落库，使一次失败可定位为长度截断 / JSON 非法 / 上下文超限中的一种。
 */
public class GenericParseException extends RuntimeException {

    private final ProtocolFailureCode code;

    public GenericParseException(ProtocolFailureCode code, String message) {
        super((code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code) + ": " + message);
        this.code = code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code;
    }

    public ProtocolFailureCode getCode() {
        return code;
    }
}
