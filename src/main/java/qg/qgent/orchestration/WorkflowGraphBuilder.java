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
 * 提供按节点定位的接缝。图支持从指定 step 节点启动（{@code startStepId}）：仍构建全部节点
 * （requeue 边需要回 DEVELOPER 节点），仅把 START 指向起始 step——用于失败步骤续跑 / 崩溃恢复。
 */
@Service
public class WorkflowGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphBuilder.class);
    private static final int DEFAULT_MAX_INFRA_RETRIES = 3;
    private static final int DEFAULT_MAX_QUALITY_LOOPS = 3;
    private static final int MAX_RECURSION_LIMIT = 10_000;

    /**
     * 节点执行器：按 step 跑一次 Agent 并返回可序列化图状态（projectId/taskId/route）。
     */
    @FunctionalInterface
    public interface NodeRunner {
        Map<String, Object> run(TaskStepEntity step, TaskOrchestrationState state);
    }

    /**
     * 按有序步骤构建并编译编排图，从第一个步骤开始执行（全量编排）。
     *
     * @param steps         按执行顺序排列的任务步骤（至少一个；PLANNER 恒在首位）。
     * @param runner        每个节点的执行体（TaskOrchestrator.runStepNode）。
     * @param requeueNodeId 质量失败回 Coding 的目标节点名（DEVELOPER step 的 stepId）。
     */
    public CompiledGraph<TaskOrchestrationState> build(List<TaskStepEntity> steps, NodeRunner runner,
                                                       String requeueNodeId) {
        return build(steps, runner, requeueNodeId, null);
    }

    /**
     * 按有序步骤构建并编译编排图，从指定 step 节点启动（续跑/重试/恢复）。
     * <p>
     * 与 {@link #build(List, NodeRunner, String)} 的区别仅在于 START 边指向：startStepId 为 null
     * 时指向首个步骤，否则指向该步骤节点。所有步骤节点与条件边仍全部构建，保证质量失败时
     * requeue 边仍能回 DEVELOPER 节点。
     *
     * @param steps         按执行顺序排列的任务步骤（至少一个）。
     * @param runner        每个节点的执行体（TaskOrchestrator.runStepNode）。
     * @param requeueNodeId 质量失败回 Coding 的目标节点名（DEVELOPER step 的 stepId）。
     * @param startStepId   起始步骤 ID；null 表示从第一个步骤开始（全量）。
     */
    public CompiledGraph<TaskOrchestrationState> build(List<TaskStepEntity> steps, NodeRunner runner,
                                                       String requeueNodeId, String startStepId) {
        return build(steps, runner, requeueNodeId, startStepId,
                DEFAULT_MAX_INFRA_RETRIES, DEFAULT_MAX_QUALITY_LOOPS);
    }

    public CompiledGraph<TaskOrchestrationState> build(List<TaskStepEntity> steps, NodeRunner runner,
                                                       String requeueNodeId, String startStepId,
                                                       int maxInfraRetries, int maxQualityLoops) {
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
            if (startStepId != null && !startStepId.isBlank()) {
                graph.addEdge(GraphDefinition.START, startStepId);
            } else {
                graph.addEdge(GraphDefinition.START, steps.get(0).getId().toString());
            }
            return graph.compile(CompileConfig.builder()
                    .recursionLimit(recursionLimit(steps.size(), maxInfraRetries, maxQualityLoops)).build());
        } catch (GraphStateException e) {
            // 图结构错误属于编程错误（节点/边名不匹配），包装为运行时异常，不改变调用方签名。
            throw new IllegalStateException("failed to build step-driven orchestration graph", e);
        }
    }

    /**
     * 最坏路径为每轮质量修复重新遍历全部步骤，且每个节点访问均可能先耗尽同相位基础设施重试。
     * 加 8 覆盖 START/END 及 LangGraph 的条件边迭代记账，并设有限硬上限防止错误配置制造无界图。
     */
    static int recursionLimit(int stepCount, int maxInfraRetries, int maxQualityLoops) {
        if (stepCount <= 0 || maxInfraRetries < 0 || maxQualityLoops < 0) {
            throw new IllegalArgumentException("graph limits must be non-negative and steps must be positive");
        }
        long visits = (long) stepCount * (maxInfraRetries + 1L) * (maxQualityLoops + 1L) + 8L;
        return (int) Math.min(visits, MAX_RECURSION_LIMIT);
    }
}
