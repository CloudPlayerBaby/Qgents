package qg.qgent.orchestration;

import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;
import qg.qgent.entity.TaskStepEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据驱动编排图构建器测试：线性链（step=node）执行顺序、requeue 回 DEVELOPER 节点、
 * retry 自环路由、空步骤拒绝。节点返回 route 值驱动条件边，与 TaskOrchestrator 行为一致。
 */
class WorkflowGraphBuilderTest {

    private static final String PID = UUID.randomUUID().toString();
    private static final String TID = UUID.randomUUID().toString();

    private final WorkflowGraphBuilder builder = new WorkflowGraphBuilder();

    private TaskStepEntity step(UUID id, String role) {
        TaskStepEntity s = new TaskStepEntity();
        s.setId(id);
        s.setRole(role);
        return s;
    }

    private Map<String, Object> route(String route) {
        return Map.of("projectId", PID, "taskId", TID, "route", route);
    }

    @Test
    void emptyStepsAreRejected() {
        assertThatThrownBy(() -> builder.build(List.of(), (s, state) -> route("next"), "nope"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void linearChainRunsEveryNodeInOrderOnce() throws Exception {
        TaskStepEntity a = step(UUID.randomUUID(), "PLANNER");
        TaskStepEntity b = step(UUID.randomUUID(), "DEVELOPER");
        TaskStepEntity c = step(UUID.randomUUID(), "TESTER");
        List<UUID> ran = new ArrayList<>();

        CompiledGraph<TaskOrchestrationState> graph = builder.build(List.of(a, b, c),
                (s, state) -> {
                    ran.add(s.getId());
                    return route("next");
                },
                b.getId().toString());

        graph.invoke(Map.of("projectId", PID, "taskId", TID));

        assertThat(ran).containsExactly(a.getId(), b.getId(), c.getId());
    }

    @Test
    void requeueRoutesBackToDeveloperNode() throws Exception {
        TaskStepEntity a = step(UUID.randomUUID(), "PLANNER");
        TaskStepEntity b = step(UUID.randomUUID(), "DEVELOPER");
        TaskStepEntity c = step(UUID.randomUUID(), "TESTER");
        TaskStepEntity d = step(UUID.randomUUID(), "REVIEWER");
        Map<UUID, AtomicInteger> visits = new ConcurrentHashMap<>();
        // TESTER 首次访问回 DEVELOPER 节点；其余恒 next
        CompiledGraph<TaskOrchestrationState> graph = builder.build(List.of(a, b, c, d), (s, state) -> {
            int n = visits.computeIfAbsent(s.getId(), k -> new AtomicInteger()).incrementAndGet();
            if (s.getId().equals(c.getId()) && n == 1) {
                return route("requeue");
            }
            return route("next");
        }, b.getId().toString());

        graph.invoke(Map.of("projectId", PID, "taskId", TID));

        assertThat(visits.get(a.getId()).get()).isEqualTo(1);
        assertThat(visits.get(b.getId()).get()).isEqualTo(2); // requeue 二次执行 DEVELOPER
        assertThat(visits.get(c.getId()).get()).isEqualTo(2);
        assertThat(visits.get(d.getId()).get()).isEqualTo(1);
    }

    @Test
    void retryRoutesBackToSelf() throws Exception {
        TaskStepEntity a = step(UUID.randomUUID(), "PLANNER");
        TaskStepEntity b = step(UUID.randomUUID(), "DEVELOPER");
        Map<UUID, AtomicInteger> visits = new ConcurrentHashMap<>();
        // DEVELOPER 首次访问自环重试；二次访问继续 next
        CompiledGraph<TaskOrchestrationState> graph = builder.build(List.of(a, b), (s, state) -> {
            int n = visits.computeIfAbsent(s.getId(), k -> new AtomicInteger()).incrementAndGet();
            if (s.getId().equals(b.getId()) && n == 1) {
                return route("retry");
            }
            return route("next");
        }, b.getId().toString());

        graph.invoke(Map.of("projectId", PID, "taskId", TID));

        assertThat(visits.get(a.getId()).get()).isEqualTo(1);
        assertThat(visits.get(b.getId()).get()).isEqualTo(2);
    }
}
