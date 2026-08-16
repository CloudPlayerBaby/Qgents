package qg.qgent.orchestration.agent;

/**
 * 模型返回的 Coding 输出非法（既非 toolCall 也非 finalResult、finalResult 缺必填字段、
 * 超循环上限等）时抛出。由 CodingAgent 捕获并转为 FAILED_INFRASTRUCTURE，使状态机进入
 * 同相位重试。
 * <p>
 * 携带稳定的 {@link ProtocolFailureCode}，随异常 message 与脱敏观测落库，使一次失败可定位为
 * 长度截断 / JSON 非法 / 未知工具 / 参数非法 / 上下文超限中的一种。
 */
public class CodingParseException extends RuntimeException {

    private final ProtocolFailureCode code;

    public CodingParseException(String message) {
        this(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED, message);
    }

    public CodingParseException(ProtocolFailureCode code, String message) {
        super((code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code) + ": " + message);
        this.code = code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code;
    }

    public ProtocolFailureCode getCode() {
        return code;
    }
}
