package qg.qgent.orchestration.tool;

/**
 * 固定开发命令的最小结果。标准输出和标准错误不会从 Worker 取回或进入模型上下文。
 */
public record DevelopmentCommandResult(boolean ok, String commandId, Integer exitCode,
                                       String failureCode, String failureReason) {

    public static DevelopmentCommandResult unavailable(DevelopmentCommandId commandId) {
        return new DevelopmentCommandResult(false, commandId == null ? null : commandId.name(), null,
                "DEVELOPMENT_COMMAND_UNAVAILABLE", "固定开发命令当前不可用");
    }
}
