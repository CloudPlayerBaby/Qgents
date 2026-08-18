package qg.qgent.orchestration.agent;

/**
 * LLM 返回的 Plan 文本非法或不完整（非 JSON、缺必填字段、步骤无文件等）时抛出。
 * <p>
 * 由 PlanAgent 捕获并转换为 FAILED_INFRASTRUCTURE，使状态机进入同相位重试，
 * 而非把一次格式错误当作不可修复的计划失败。
 */
public class PlanParseException extends RuntimeException {

    private final ProtocolFailureCode code;

    public PlanParseException(String message) {
        this(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED, message);
    }

    public PlanParseException(ProtocolFailureCode code, String message) {
        super((code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code) + ": " + message);
        this.code = code == null ? ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED : code;
    }

    public ProtocolFailureCode getCode() {
        return code;
    }
}
