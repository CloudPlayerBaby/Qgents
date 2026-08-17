package qg.qgent.orchestration.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 Agent 运行时：以 {@link AgentEntity#prompt} 作系统提示，按能力→工具白名单
 * （{@link CapabilityToolRegistry}，写能力默认拒绝）暴露工具，经 {@link LlmClient#nextToolTurn}
 * 原生 Tool Calling 循环执行，最终文本必须为 JSON {@code {"success": bool, "summary": "...", "message": "..."}}
 * （{@link GenericResultParser} 校验）。
 * <p>
 * 结果分类：
 * <ul>
 *   <li>success=true → SUCCEEDED；</li>
 *   <li>success=false → FAILED_QUALITY（专项检查视为质量门禁，由状态机回 Coding 修复）；</li>
 *   <li>输出非法、缺必填字段、超循环上限、输出被截断等协议失败 → FAILED_INFRASTRUCTURE
 *       （同相位重试）。</li>
 * </ul>
 * 每轮模型调用生成一条脱敏观测 {@link LlmObservation} 随 Run 产物落库。写工具命中的自定义
 * Agent（含写能力）同样把成功写结果交给 {@link CodingWriteObserver}（可为 null，预览静默跳过）。
 * 不执行 Git 或沙箱命令；工具结果与错误经既有 Coding/Review 工具结构保证路径约束与脱敏。
 */
@Slf4j
public class GenericCustomAgent implements Agent {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final CapabilityToolRegistry toolRegistry;
    private final AgentEntity entity;
    private final CodingWriteObserver writeObserver;
    private final GenericResultParser parser = new GenericResultParser();

    public GenericCustomAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, CapabilityToolRegistry toolRegistry,
                              AgentEntity entity, CodingWriteObserver writeObserver) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.toolRegistry = toolRegistry;
        this.entity = entity;
        this.writeObserver = writeObserver;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        boolean writeCapable = toolRegistry.hasWriteCapability(entity.getCapabilities());
        log.info("custom agent start agentId={} role={} write={} phase={} workspaceId={}",
                entity.getId(), entity.getRole(), writeCapable, input.getPhase(), input.getWorkspaceId());
        List<LlmObservation> observations = new ArrayList<>();
        try {
            CustomResult result = executeCustom(input, observations, writeCapable);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setOutcome(result.success() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED_QUALITY);
            outcome.setMessage(pickMessage(result));
            outcome.setObservations(observations);
            log.info("custom agent done agentId={} phase={} outcome={} observations={}",
                    entity.getId(), input.getPhase(), outcome.getOutcome(), observations.size());
            return outcome;
        } catch (RuntimeException e) {
            log.error("CUSTOM_AGENT_FAILED agentId={} phase={} category={}",
                    entity.getId(), input.getPhase(), e.getClass().getSimpleName());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setMessage("custom agent failed: " + e.getMessage());
            failure.setObservations(observations);
            return failure;
        }
    }

    /**
     * 原生 Tool Calling 循环：每轮把历史（含 tool responses）回传给模型，直到输出最终 JSON。
     * 每轮写入一条脱敏观测；工具执行遇到基础设施失败抛 {@link IllegalStateException}，由 run()
     * 统一转为 FAILED_INFRASTRUCTURE。
     */
    private CustomResult executeCustom(AgentInput input, List<LlmObservation> observations, boolean writeCapable) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(buildUser(input, files)));
        String system = buildSystem(writeCapable);
        Object tools = toolRegistry.toolsFor(input.getWorkspaceId(), entity.getCapabilities());
        if (tools instanceof CodingTools codingTools) {
            codingTools.setWriteObserver(writeObserver, input.getProjectId(), input.getTaskId(), input.getTaskRunId());
        }
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools));
        String finishReason = null;
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            ToolTurnResult turn = llm.nextToolTurn(system, history, callbacks);
            observations.add(LlmObservation.of(input.getPhase().name(), round, turn));
            finishReason = turn.finishReason();
            if (turn.isInfraAbort()) {
                log.error("CUSTOM_INFRA_ABORT agentId={} phase={} workspaceId={} round={} tool={} reason={}",
                        entity.getId(), input.getPhase(), input.getWorkspaceId(), round, turn.toolName(),
                        turn.infraFailure());
                throw new IllegalStateException(turn.infraFailure());
            }
            if (turn.continuesToolLoop()) {
                history = turn.history();
                continue;
            }
            if (turn.isFinalText()) {
                if ("length".equals(finishReason)) {
                    throw new GenericParseException(ProtocolFailureCode.LLM_FINISH_LENGTH,
                            "custom agent output truncated by max tokens");
                }
                log.info("custom agent round {} finalResult agentId={} phase={} workspaceId={}",
                        round, entity.getId(), input.getPhase(), input.getWorkspaceId());
                return parser.parse(turn.text());
            }
            throw new GenericParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "custom agent tool turn returned no text, history or infra failure");
        }
        throw new GenericParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    /**
     * 系统提示：Agent 自定义 prompt + 工具协议（按写能力切换工具清单）+ 输出契约。
     */
    private String buildSystem(boolean writeCapable) {
        String prompt = entity.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = "你是多智能体协作平台中的自定义 Agent「" + entity.getName() + "」。";
        }
        return prompt.strip() + "\n\n"
                + (writeCapable ? WRITE_TOOLS_CONTRACT : READ_ONLY_TOOLS_CONTRACT)
                + "\n\n工作方式：\n"
                + "- 先按需调用工具理解现状，只读取需要的文件；工具返回 ok=false 时根据 error 修正后重试。\n"
                + "- 完成后输出 JSON（不要输出代码围栏）：{\"success\": true|false, \"summary\": \"结果摘要\", \"message\": \"给用户的具体反馈、发现的问题或建议\"}\n"
                + "- 无法完成、或发现不满足验收条件时 success=false，message 说明原因。";
    }

    /**
     * 用户消息：任务上下文 + 步骤指令 + 结构化计划（如有）+ 工作区文件树。
     */
    private String buildUser(AgentInput input, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题：").append(nullToBlank(input.getTaskTitle()));
        sb.append("\n任务描述：").append(nullToBlank(input.getRequirement()));
        sb.append("\n步骤指令：").append(nullToBlank(input.getInstruction()));
        if (input.getPlanResult() != null) {
            appendPlan(sb, input.getPlanResult());
        }
        if (input.getFeedback() != null && !input.getFeedback().isBlank()) {
            sb.append("\n前一轮反馈：").append(input.getFeedback());
        }
        sb.append("\n\n工作区文件树：\n").append(renderTree(files));
        sb.append(ContextPromptRenderer.render(input));
        return sb.toString();
    }

    private void appendPlan(StringBuilder sb, PlanResult plan) {
        sb.append("\n\n实现计划：");
        if (plan.getObjectives() != null && !plan.getObjectives().isEmpty()) {
            sb.append("\n- 实现目标：").append(String.join("；", plan.getObjectives()));
        }
        if (plan.getImplementationSteps() != null) {
            for (PlanResult.ImplementationStep step : plan.getImplementationSteps()) {
                sb.append("\n  * ").append(nullToBlank(step.getTitle()));
                if (step.getFiles() != null && !step.getFiles().isEmpty()) {
                    sb.append(" 文件：").append(String.join(",", step.getFiles()));
                }
            }
        }
        if (plan.getTestPlan() != null && !plan.getTestPlan().isBlank()) {
            sb.append("\n- 测试计划：").append(plan.getTestPlan());
        }
    }

    private String renderTree(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "(空，未检测到代码文件)";
        }
        return files.stream().map(file -> "- " + file).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private String pickMessage(CustomResult result) {
        if (result.message() != null && !result.message().isBlank()) {
            return result.message();
        }
        return result.summary() == null || result.summary().isBlank()
                ? (result.success() ? "custom agent finished" : "custom agent found issues") : result.summary();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static final String READ_ONLY_TOOLS_CONTRACT = """
            可用工具（通过原生函数调用使用，直接给出参数，不要包裹任何 JSON 文本）：
            - list_files：列出工作区所有代码文件，无参数。
            - read_file：读取文件内容与当前 sha256，参数 {"path": "相对路径"}。
            - search_code：检索关键字命中的文件路径，参数 {"query": "关键字"}。
            """;

    private static final String WRITE_TOOLS_CONTRACT = READ_ONLY_TOOLS_CONTRACT + """
            - apply_patch：对已有文本文件精确应用统一 Diff，参数 {"path": "相对路径", "expectedHash": "read_file 返回的 64 位十六进制 sha256", "patch": "统一 Diff 文本"}；expectedHash 必须来自同一次 read_file。
            - write_file：创建新文件，参数 {"path": "相对路径", "content": "文件内容"}；目标文件已存在时会被拒绝，改用 apply_patch。
            """;
}
