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
import qg.qgent.orchestration.agent.PromptBoundAgent;
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
 *       其余（自定义 Agent）且角色在内置映射内（PLANNER/DEVELOPER/TESTER/REVIEWER）时，以
 *       {@link PromptBoundAgent} 装饰内置引擎并叠加自定义 prompt 作为补充指引；自定义角色无内置
 *       映射时以 {@link GenericCustomAgent} 包装（DB prompt + 角色→工具白名单）；实体不存在 → 空
 *       （调用方跳步，不硬跑）；</li>
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
        return resolve(agentId, role, null);
    }

    /**
     * 解析步骤 Agent。执行模式可覆盖默认角色映射，避免 DEVELOPER 角色的 VERIFY
     * 步骤拿到 Coding Agent 的写权限。
     */
    public Optional<Agent> resolve(UUID agentId, String role, String executionMode) {
        if (agentId == null) {
            Agent builtin = builtin(role, executionMode);
            return builtin == null ? Optional.empty() : Optional.of(builtin);
        }
        AgentEntity entity = agentMapper.selectById(agentId);
        if (entity == null) {
            return Optional.empty();
        }
        // 先按实体声明角色解析内置引擎（保留 executionMode 对 VERIFY/TEST/REVIEW 的覆盖）：
        // 团队默认 Agent（isDefault=true，系统预置、用户不可编辑）直接复用内置 Agent 类，使用其
        // 详细系统提示与专属解析器（内置 Agent 为无状态单例，可变数据全在 run() 方法内局部创建）；
        // 自定义 Agent 且角色有内置映射时，用 PromptBoundAgent 装饰内置引擎并叠加自定义 prompt 作为
        // 补充指引——内置确定性门禁（真实 exit code / 严重度策略 / 写证据 / 结构化校验）保持不变。
        // 仅当角色不在内置映射内（自定义标签或防御路径）时回退通用自定义运行时，避免缺 Agent 挂起。
        Agent builtin = builtin(entity.getRole(), executionMode);
        if (builtin != null) {
            if (Boolean.TRUE.equals(entity.getIsDefault())) {
                return Optional.of(builtin);
            }
            return Optional.of(new PromptBoundAgent(builtin, entity.getPrompt()));
        }
        return Optional.of(new GenericCustomAgent(llm, codeAccess, toolRegistry, entity, writeObserver,
                contextService, contextSearchProperties));
    }

    /**
     * 内置兜底：仅模板角色映射到内置 Agent，自定义角色无内置实现。
     */
    private Agent builtin(String role) {
        return builtin(role, null);
    }

    private Agent builtin(String role, String executionMode) {
        if (role == null) {
            return null;
        }
        // 未知 role 不得因为 TaskStepExecutionMode 的安全默认 VERIFY 被误映射成
        // TestAgent；未知内置角色应继续走原有“无 Agent”路径，避免静默跳过自定义配置。
        boolean knownRole = switch (role) {
            case "PLANNER", "DEVELOPER", "TESTER", "REVIEWER" -> true;
            default -> false;
        };
        if (!knownRole) {
            return null;
        }
        TaskStepExecutionMode mode = TaskStepExecutionMode.resolve(executionMode, role);
        if (mode == TaskStepExecutionMode.VERIFY || mode == TaskStepExecutionMode.TEST) {
            return testAgent;
        }
        if (mode == TaskStepExecutionMode.REVIEW) {
            return reviewAgent;
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
