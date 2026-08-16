package qg.qgent.orchestration.tool;

/**
 * Workspace 写工具的基础设施级失败标记：workspace 不可用、文件系统错误等重试同一写操作
 * 无意义的失败。由 {@link CodingTools} 写方法抛出，经原生工具调用层识别后中止 Agent 循环，
 * 映射 FAILED_INFRASTRUCTURE（不进入模型纠正循环）。
 * <p>
 * 区别于工具级失败（返回 {@code ok=false} 结构让模型自行纠正）。message 不得包含宿主机
 * 绝对路径或 Secret（{@link WorkspaceWriteResult#getError()} 已由写端口脱敏）。
 */
public class WorkspaceInfraException extends RuntimeException {

    public WorkspaceInfraException(String message) {
        super(message);
    }
}
