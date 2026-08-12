package qg.qgent.orchestration.tool;

/**
 * 一次命令执行的结果（内部值对象，非持久化 Entity、非接口 DTO）。
 * 输出必须已经脱敏，不得携带 Secret。
 */
public record ExecutionResult(boolean ok, int exitCode, String stdout, String stderr, String error) {

    public static ExecutionResult unavailable() {
        return new ExecutionResult(false, -1, "", "", "command execution is not available until sandbox is ready");
    }
}
