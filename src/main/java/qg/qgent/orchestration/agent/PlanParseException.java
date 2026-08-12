package qg.qgent.orchestration.agent;

/**
 * LLM 返回的 Plan 文本非法或不完整（非 JSON、缺必填字段、步骤无文件等）时抛出。
 * <p>
 * 由 PlanAgent 捕获并转换为 FAILED_INFRASTRUCTURE，使状态机进入同相位重试，
 * 而非把一次格式错误当作不可修复的计划失败。
 */
public class PlanParseException extends RuntimeException {

    public PlanParseException(String message) {
        super(message);
    }
}
