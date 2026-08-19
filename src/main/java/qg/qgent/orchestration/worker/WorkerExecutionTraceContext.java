package qg.qgent.orchestration.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 TaskRun 收集 Worker 工具执行摘要，供编排完成后建立受控诊断关联。
 * <p>
 * 这里只暂存执行 ID 和 Worker 返回的终态元数据，不保存工具参数、文件内容、stdout 或 stderr。
 * Agent 在线程池中执行，因此使用 TaskRun ID 关联，不能依赖编排线程的 ThreadLocal。
 */
public final class WorkerExecutionTraceContext {
    private static final ConcurrentHashMap<UUID, Trace> TRACES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_RUN_ID = new ThreadLocal<>();

    private WorkerExecutionTraceContext() {
    }

    /** 在 Agent 执行线程中开始收集指定 TaskRun 的 Worker 摘要。 */
    public static void begin(UUID taskRunId) {
        if (taskRunId == null) {
            CURRENT.remove();
            return;
        }
        Trace trace = new Trace();
        TRACES.put(taskRunId, trace);
        CURRENT.set(trace);
        CURRENT_RUN_ID.set(taskRunId);
    }

    /** 记录一次 Worker 工具执行；同一 ID 的后续终态会替换入队摘要。 */
    public static void record(WorkerToolExecution execution) {
        Trace trace = CURRENT.get();
        if (trace != null && execution != null) {
            trace.add(execution);
        }
    }

    /** 清理 Agent 执行线程绑定；摘要仍保留到编排线程按 TaskRun ID 取走。 */
    public static void detach() {
        CURRENT.remove();
        CURRENT_RUN_ID.remove();
    }

    /** 当前 Agent 线程所属的 TaskRun；Worker 接收执行后立即落库关联时使用。 */
    public static UUID currentTaskRunId() {
        return CURRENT_RUN_ID.get();
    }

    /** 取出并移除一个 TaskRun 的 Worker 摘要。 */
    public static List<WorkerToolExecution> drain(UUID taskRunId) {
        if (taskRunId == null) {
            return List.of();
        }
        Trace trace = TRACES.remove(taskRunId);
        return trace == null ? List.of() : trace.closeAndSnapshot();
    }

    private static final class Trace {
        private final Map<UUID, WorkerToolExecution> executions = new LinkedHashMap<>();
        private boolean closed;

        private synchronized void add(WorkerToolExecution execution) {
            if (!closed && execution.getId() != null) {
                executions.put(execution.getId(), execution);
            }
        }

        private synchronized List<WorkerToolExecution> closeAndSnapshot() {
            closed = true;
            return List.copyOf(executions.values());
        }
    }
}
