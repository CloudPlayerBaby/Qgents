package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实 Coding Agent：理解任务与计划，通过只读工具按需读取工作区代码，用 apply_patch
 * 精确修改已有文件、用 write_file 新建文件，真正修改工作区文件，并输出结构化
 * finalResult 生成 {@link CodingResult}。
 * <p>
 * 阶段 B 起默认走原生 Tool Calling（{@code app.agent.protocol=native}）：模型直接返回结构化
 * {@code toolCall}，由 {@link LlmClient#nextToolTurn} 执行工具并原样回灌历史；模型输出最终文本
 * 后由 {@link CodingResultParser#parse(String)} 解析 finalResult。legacy 协议（手写 JSON）仅灰度期
 * 保留，稳定后删除。工具协议由 {@link AgentProtocol} 开关切换。
 * <p>
 * 结果分类：合法 finalResult 按 success 映射 SUCCEEDED / FAILED；输出非法、缺必填字段、
 * 超循环上限等协议失败抛 {@link CodingParseException}（携带 {@link ProtocolFailureCode}），
 * 统一转为 FAILED_INFRASTRUCTURE，由状态机决定同相位重试。每轮模型调用生成一条脱敏观测
 * {@link LlmObservation} 随 Run 产物落库。只经 {@link WorkspaceCodeWriter} 写工作区，不执行
 * Git 或沙箱命令。
 */
@Slf4j
@Component
public class CodingAgent implements Agent {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final AgentProtocol protocol;
    private final CodingPromptBuilder promptBuilder = new CodingPromptBuilder();
    private final CodingResultParser parser = new CodingResultParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 成功写后的预览回调（阶段 D），可空；Spring 注入 {@link CodingWriteObserver} 实现，
     * 未配置时 Coding 工具静默跳过，不影响编码流程。
     */
    private CodingWriteObserver writeObserver;

    public CodingAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       AgentProtocol protocol) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.protocol = protocol;
    }

    /**
     * 可选回调注入：存在 {@link CodingWriteObserver} Bean 时由 Spring 调用。
     */
    @Autowired(required = false)
    public void setWriteObserver(CodingWriteObserver writeObserver) {
        this.writeObserver = writeObserver;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        log.info("coding agent start phase={} workspaceId={} protocol={}",
                input.getPhase(), input.getWorkspaceId(), protocol.isNative() ? "native" : "legacy");
        List<LlmObservation> observations = new ArrayList<>();
        try {
            CodingResult coding = protocol.isNative()
                    ? executeCodingNative(input, observations)
                    : executeCodingLegacy(input);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setOutcome(coding.isSuccess() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED);
            outcome.setCodingResult(coding);
            outcome.setMessage(coding.isSuccess() ? coding.getSummary() : firstError(coding));
            outcome.setObservations(observations);
            log.info("coding agent done phase={} workspaceId={} outcome={} observations={}",
                    input.getPhase(), input.getWorkspaceId(), outcome.getOutcome(), observations.size());
            return outcome;
        } catch (RuntimeException e) {
            log.error("CODING_AGENT_FAILED phase={} workspaceId={} category={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setMessage("coding agent failed: " + e.getMessage());
            failure.setObservations(observations);
            return failure;
        }
    }

    /**
     * 原生 Tool Calling 循环：每轮把历史（含 tool responses）回传给模型，直到输出 finalResult。
     * 每轮写入一条脱敏观测；工具执行遇到基础设施失败（Workspace 不可用）抛
     * {@link IllegalStateException}，由 run() 统一转为 FAILED_INFRASTRUCTURE。
     */
    private CodingResult executeCodingNative(AgentInput input, List<LlmObservation> observations) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem(true);
        CodingTools tools = new CodingTools(input.getWorkspaceId(), codeAccess, writer);
        tools.setWriteObserver(writeObserver, input.getProjectId(), input.getTaskId(), input.getTaskRunId());
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools));
        String finishReason = null;
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            ToolTurnResult turn = llm.nextToolTurn(system, history, callbacks);
            observations.add(LlmObservation.of(input.getPhase().name(), round, turn));
            finishReason = turn.finishReason();
            if (turn.isInfraAbort()) {
                log.error("CODING_INFRA_ABORT phase={} workspaceId={} round={} tool={} reason={}",
                        input.getPhase(), input.getWorkspaceId(), round, turn.toolName(), turn.infraFailure());
                throw new IllegalStateException(turn.infraFailure());
            }
            if (turn.continuesToolLoop()) {
                history = turn.history();
                continue;
            }
            if (turn.isFinalText()) {
                if ("length".equals(finishReason)) {
                    throw new CodingParseException(ProtocolFailureCode.LLM_FINISH_LENGTH,
                            "coding output truncated by max tokens");
                }
                log.info("coding agent round {} finalResult phase={} workspaceId={}",
                        round, input.getPhase(), input.getWorkspaceId());
                return parser.parse(turn.text());
            }
            throw new CodingParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "coding tool turn returned no text, history or infra failure");
        }
        throw new CodingParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    /**
     * legacy 手写 JSON 协议循环：模型输出 toolCall/finalResult 文本，由 {@link CodingToolExecutor}
     * 执行工具。仅灰度期使用，协议切换稳定后删除。
     */
    private CodingResult executeCodingLegacy(AgentInput input) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent (legacy) workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem(false);
        CodingToolExecutor toolExecutor = new CodingToolExecutor(codeAccess, writer);
        toolExecutor.setWriteObserver(writeObserver, input.getProjectId(), input.getTaskId(),
                input.getTaskRunId(), input.getWorkspaceId());
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            String raw = llm.complete(system, history);
            history.add(LlmMessage.assistant(raw));
            JsonNode node = toJson(raw);
            JsonNode toolCall = node.get("toolCall");
            if (toolCall != null && toolCall.isObject()) {
                log.info("coding agent round {} tool={} phase={} workspaceId={}",
                        round, toolCall.path("name").asText("?"), input.getPhase(), input.getWorkspaceId());
                history.add(LlmMessage.tool(toolExecutor.execute(input.getWorkspaceId(), toolCall)));
                continue;
            }
            JsonNode finalResult = node.get("finalResult");
            if (finalResult != null && finalResult.isObject()) {
                log.info("coding agent round {} finalResult phase={} workspaceId={}",
                        round, input.getPhase(), input.getWorkspaceId());
                return parser.parse(finalResult);
            }
            throw new CodingParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "coding output is neither toolCall nor finalResult");
        }
        throw new CodingParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    private JsonNode toJson(String raw) {
        try {
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new CodingParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "coding output is not valid JSON: " + e.getMessage());
        }
    }

    private String firstError(CodingResult coding) {
        if (coding.getErrors() != null && !coding.getErrors().isEmpty()) {
            return coding.getErrors().get(0);
        }
        return coding.getSummary() == null ? "coding incomplete" : coding.getSummary();
    }

    /**
     * 去掉常见的 ```json / ``` 围栏包裹。
     */
    private String stripFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
