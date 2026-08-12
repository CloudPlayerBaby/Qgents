package qg.qgent.sandboxworker.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 底层命令执行结果。
 * 输出在进入该对象前已经由运行时实施大小限制。
 */
@Data
@AllArgsConstructor
public class CommandExecutionResult {

    /** 进程退出码；底层运行时无法取得时使用 -1。 */
    private int exitCode;

    /** 按行拆分的标准输出。 */
    private List<String> standardOutput;

    /** 按行拆分的标准错误输出。 */
    private List<String> standardError;
}
