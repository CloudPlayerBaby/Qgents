package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 调用沙箱工具时提交的结构化请求（镜像 Worker 的 ToolExecutionRequest）。
 * arguments 为工具白名单内定义的键值参数，不携带宿主路径或凭证。
 */
@Data
public class WorkerToolExecutionRequest {

    /** 由控制层生成的执行唯一编号；重复编号返回冲突。 */
    private UUID executionId;

    /** 目标仓库编号；process.exec 等不需要仓库的工具可为 null。 */
    private UUID repositoryId;

    /** 工具名，形如 {@code file.read} / {@code process.exec}。 */
    private String tool;

    /** 工具参数。 */
    private Map<String, Object> arguments;

    /** 单次执行超时秒数（可选）。 */
    private Long timeoutSeconds;
}
