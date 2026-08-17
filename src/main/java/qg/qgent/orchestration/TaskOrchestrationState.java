package qg.qgent.orchestration;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.UUID;

/**
 * StateGraph 节点间传递的图状态视图。LangGraph4j 默认状态序列化走 Java ObjectStream，
 * 且 {@link AgentState} 不实现 Serializable，因此图状态只承载可序列化基本值：
 * projectId/taskId 用于定位执行现场，route 用于条件边路由。
 * <p>
 * 从 TaskOrchestrator 抽出为包内独立类，供 {@link WorkflowGraphBuilder} 数据驱动建图使用。
 */
final class TaskOrchestrationState extends AgentState {

    TaskOrchestrationState(Map<String, Object> data) {
        super(data);
    }

    UUID getProjectId() {
        return UUID.fromString(this.<String>value("projectId").orElse(""));
    }

    UUID getTaskId() {
        return UUID.fromString(this.<String>value("taskId").orElse(""));
    }
}
