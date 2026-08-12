package qg.qgent.orchestration.agent;

/**
 * 模型返回的 Coding 输出非法（既非 toolCall 也非 finalResult、finalResult 缺必填字段等）时抛出。
 * 由 CodingAgent 捕获并转为 FAILED_INFRASTRUCTURE，使状态机进入同相位重试。
 */
public class CodingParseException extends RuntimeException {

    public CodingParseException(String message) {
        super(message);
    }
}
