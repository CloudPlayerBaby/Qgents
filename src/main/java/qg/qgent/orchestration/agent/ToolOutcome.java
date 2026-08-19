package qg.qgent.orchestration.agent;

/**
 * 一次写工具调用的脱敏结果事实（apply_patch / write_file / create_directory）：
 * 只记录 ok、changed、errorCode、retryable 与简短错误说明，不携带 patch、文件内容或
 * 宿主机绝对路径。CodingTools 每轮收集，CodingAgent 汇入 {@link ChangedWriteFactLedger}，
 * 供「零变更失败」门禁汇总本次 run 的尝试历史，并为服务端日志提供逐次可观测性。
 */
public record ToolOutcome(
        String toolName,
        String path,
        boolean ok,
        boolean changed,
        String errorCode,
        boolean retryable,
        String error) {
}
