package qg.qgent.orchestration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import qg.qgent.entity.AgentEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.orchestration.agent.AgentToolRegistry;
import qg.qgent.orchestration.agent.CodingAgent;
import qg.qgent.orchestration.agent.CodingWriteObserver;
import qg.qgent.orchestration.agent.ContextSearchProperties;
import qg.qgent.orchestration.agent.GenericCustomAgent;
import qg.qgent.orchestration.agent.PlanAgent;
import qg.qgent.orchestration.agent.ReviewAgent;
import qg.qgent.orchestration.agent.TestAgent;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.service.ContextService;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent 运行时注册表：按 TaskStep 的 assignedAgentId 解析要执行的 Agent，取代
 * {@code AgentRunExecutor} 的 Map&lt;Phase, Agent&gt;。
 * <ul>
 *   <li>{@code assignedAgentId == null}（内置兜底）：按角色取内置 Agent——PLANNER→PlanAgent、
 *       DEVELOPER→CodingAgent、TESTER→TestAgent、REVIEWER→ReviewAgent；角色未知 → 空；</li>
 *   <li>{@code assignedAgentId != null}：查 {@link AgentEntity}——团队默认 Agent（isDefault=true，
 *       系统预置、用户不可编辑）直接复用对应内置 Agent 类（详细系统提示 + 专属解析器）；
 *       其余（自定义 Agent）以 {@link GenericCustomAgent} 包装（DB prompt + 角色→工具白名单）；
 *       实体不存在 → 空（调用方跳步，不硬跑）；</li>
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
    private final AgentToolRegistry toolRegistry;
    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    /**
     * 自定义 Agent 运行时 Skill 激活与当前群聊天检索的服务端入口。
     */
    private final ContextService contextService;
    /**
     * 自定义 Agent 每次 TaskRun 检索工具的调用次数上限配置。
     */
    private final ContextSearchProperties contextSearchProperties;
    /**
     * 成功写后的预览回调（阶段 D），可空；由 {@link GenericCustomAgent} 注入写工具。
     */
    private CodingWriteObserver writeObserver;

    public AgentRegistry(PlanAgent planAgent, CodingAgent codingAgent, TestAgent testAgent, ReviewAgent reviewAgent,
                         AgentMapper agentMapper, AgentToolRegistry toolRegistry, LlmClient llm,
                         WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                         ContextService contextService, ContextSearchProperties contextSearchProperties) {
        this.planAgent = planAgent;
        this.codingAgent = codingAgent;
        this.testAgent = testAgent;
        this.reviewAgent = reviewAgent;
        this.agentMapper = agentMapper;
        this.toolRegistry = toolRegistry;
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.contextService = contextService;
        this.contextSearchProperties = contextSearchProperties;
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
        // 团队默认 Agent（isDefault=true，系统预置、用户不可编辑）：直接复用对应内置 Agent 类，
        // 使用其详细系统提示与专属解析器，保证「团队默认四 Agent」就是代码内置实现（并发安全：
        // 内置 Agent 为无状态单例，可变数据全在 run() 方法内局部创建）。仅当角色不在内置映射内
        // （防御路径，理论上不出现）时回退通用自定义运行时，避免缺 Agent 挂起。
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            Agent builtin = builtin(entity.getRole());
            if (builtin != null) {
                return Optional.of(builtin);
            }
        }
        return Optional.of(new GenericCustomAgent(llm, codeAccess, toolRegistry, entity, writeObserver,
                contextService, contextSearchProperties));
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
