package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 向兼容版异步命令接口提交的执行请求。
 * 命令必须表示为参数数组，Worker 不接受拼接后的 shell 字符串。
 */
@Data
public class CreateExecutionRequest {

    /**
     * 命令ID
     */
    @NotNull
    private UUID executionId;

    /**
     * 命令数组，第一项为可执行文件。
     */
    @NotEmpty
    @Size(max = 64)
    private List<@Size(min = 1, max = 4096) String> command;

    /**
     * 可选执行超时秒数，实际值仍受沙箱与 Worker 本地上限约束。
     */
    @Min(1)
    private Long timeoutSeconds;
}
