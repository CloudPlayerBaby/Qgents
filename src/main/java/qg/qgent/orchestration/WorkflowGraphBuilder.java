package qg.qgent.orchestration;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.entity.TaskStepEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据驱动编排图构建器：按任务的步骤列表动态构建 LangGraph4j StateGraph，取代构造器写死
 * plan/coding/test/review 四节点的旧图。每个 step 一个节点（节点名 = stepId），按 sequence 序
 * 线性链执行，条件边由 TaskOrchestrator 写入的 route 值决定下一步：
 * <ul>
 *   <li>{@code next} → 按序下一节点（末节点 → END）；</li>
 *   <li>{@code retry} → 自身（同相位基础设施重试）；</li>
 *   <li>{@code requeue} → DEVELOPER 节点（Test/Review/专项检查质量失败回 Coding 修复）；</li>
 *   <li>{@code END} → 终态。</li>
 * </ul>
 * 每次 orchestrate 按该任务的步骤现建图；步骤即节点，为后续 checkpoint / Agent 打断 / 重试
 * 提供按节点定位的接缝。
 */
@Service
public class WorkflowGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphBuilder.class);

    /**
     * 节点执行器：按 step 跑一次 Agent 并返回可序列化图状态（projectId/taskId/route）。
     */
    @FunctionalInterface
    public interface NodeRunner {
        Map<String, Object> run(TaskStepEntity step, TaskOrchestrationState state);
    }

    /**
     * 按有序步骤构建并编译编排图。
     *
     * @param steps         按执行顺序排列的任务步骤（至少一个；PLANNER 恒在首位）。
     * @param runner        每个节点的执行体（TaskOrchestrator.runStepNode）。
     * @param requeueNodeId 质量失败回 Coding 的目标节点名（DEVELOPER step 的 stepId）。
     */
    public CompiledGraph<TaskOrchestrationState> build(List<TaskStepEntity> steps, NodeRunner runner,
                                                       String requeueNodeId) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalStateException("cannot build orchestration graph without steps");
        }
        try {
            StateGraph<TaskOrchestrationState> graph = new StateGraph<>(TaskOrchestrationState::new);
            AsyncEdgeAction<TaskOrchestrationState> route = AsyncEdgeAction.edge_async(
                    (TaskOrchestrationState state) -> state.<String>value("route").orElse(GraphDefinition.END));
            for (int i = 0; i < steps.size(); i++) {
                TaskStepEntity step = steps.get(i);
                String nodeId = step.getId().toString();
                String next = i + 1 < steps.size() ? steps.get(i + 1).getId().toString() : GraphDefinition.END;
                graph.addNode(nodeId, AsyncNodeAction.node_async(
                        (TaskOrchestrationState state) -> runner.run(step, state)));
                Map<String, String> routes = new HashMap<>();
                routes.put("next", next);
                routes.put("retry", nodeId);
                routes.put("requeue", requeueNodeId);
                routes.put(GraphDefinition.END, GraphDefinition.END);
                graph.addConditionalEdges(nodeId, route, routes);
            }
            graph.addEdge(GraphDefinition.START, steps.get(0).getId().toString());
            // 循环上限远高于状态机自身的质量/基础设施重试上限，避免框架先于业务计数终止。
            return graph.compile(CompileConfig.builder().recursionLimit(64).build());
        } catch (GraphStateException e) {
            // 图结构错误属于编程错误（节点/边名不匹配），包装为运行时异常，不改变调用方签名。
            throw new IllegalStateException("failed to build step-driven orchestration graph", e);
        }
    }
}
