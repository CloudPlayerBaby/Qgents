package qg.qgent.orchestration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import qg.qgent.entity.AgentEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.orchestration.agent.CapabilityToolRegistry;
import qg.qgent.orchestration.agent.CodingAgent;
import qg.qgent.orchestration.agent.CodingWriteObserver;
import qg.qgent.orchestration.agent.GenericCustomAgent;
import qg.qgent.orchestration.agent.PlanAgent;
import qg.qgent.orchestration.agent.ReviewAgent;
import qg.qgent.orchestration.agent.TestAgent;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent 运行时注册表：按 TaskStep 的 assignedAgentId 解析要执行的 Agent，取代
 * {@code AgentRunExecutor} 的 Map&lt;Phase, Agent&gt;。
 * <ul>
 *   <li>{@code assignedAgentId == null}（内置兜底）：按角色取内置 Agent——PLANNER→PlanAgent、
 *       DEVELOPER→CodingAgent、TESTER→TestAgent、REVIEWER→ReviewAgent；角色未知 → 空；</li>
 *   <li>{@code assignedAgentId != null}：查 {@link AgentEntity}，存在则以 {@link GenericCustomAgent}
 *       包装（自定义 prompt + 能力→工具白名单）；实体不存在 → 空（调用方跳步，不硬跑）；</li>
 *   <li>角色匹配 / ACTIVE / 可见性的静态授权已在 {@link qg.qgent.service.TaskService#validateAgent}
 *       落库时校验，运行时只做存在性检查。</li>
 * </ul>
 * 内置 4 个 + 自定义 N 个统一按 id 解析，为「一个 step 一个节点跑一个 Agent」的数据驱动图提供运行时。
 */
@Service
public class AgentRegistry {

    private final PlanAgent planAgent;
    private final CodingAgent codingAgent;
    private final TestAgent testAgent;
    private final ReviewAgent reviewAgent;
    private final AgentMapper agentMapper;
    private final CapabilityToolRegistry toolRegistry;
    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    /**
     * 成功写后的预览回调（阶段 D），可空；由 {@link GenericCustomAgent} 注入写工具。
     */
    private CodingWriteObserver writeObserver;

    public AgentRegistry(PlanAgent planAgent, CodingAgent codingAgent, TestAgent testAgent, ReviewAgent reviewAgent,
                         AgentMapper agentMapper, CapabilityToolRegistry toolRegistry, LlmClient llm,
                         WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.planAgent = planAgent;
        this.codingAgent = codingAgent;
        this.testAgent = testAgent;
        this.reviewAgent = reviewAgent;
        this.agentMapper = agentMapper;
        this.toolRegistry = toolRegistry;
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
    }

    /**
     * 可选回调注入：存在 {@link CodingWriteObserver} Bean 时由 Spring 调用，透传给自定义 Agent 的写工具。
     */
    @Autowired(required = false)
    public void setWriteObserver(CodingWriteObserver writeObserver) {
        this.writeObserver = writeObserver;
    }

    /**
     * 解析要执行的 Agent。空 Optional 表示缺 Agent（自定义实体缺失或内置角色未知），
     * 调用方按「缺 Agent 不硬跑」跳过该 step。
     *
     * @param agentId step 已定型的 Agent ID；null 表示内置兜底。
     * @param role    step 声明的角色（PLANNER/DEVELOPER/TESTER/REVIEWER 或自定义标签）。
     */
    public Optional<Agent> resolve(UUID agentId, String role) {
        if (agentId == null) {
            Agent builtin = builtin(role);
            return builtin == null ? Optional.empty() : Optional.of(builtin);
        }
        AgentEntity entity = agentMapper.selectById(agentId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new GenericCustomAgent(llm, codeAccess, toolRegistry, entity, writeObserver));
    }

    /**
     * 内置兜底：仅模板角色映射到内置 Agent，自定义角色无内置实现。
     */
    private Agent builtin(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "PLANNER" -> planAgent;
            case "DEVELOPER" -> codingAgent;
            case "TESTER" -> testAgent;
            case "REVIEWER" -> reviewAgent;
            default -> null;
        };
    }
}
