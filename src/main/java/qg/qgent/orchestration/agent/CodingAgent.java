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
import qg.qgent.orchestration.tool.Sha256;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.service.ContextService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * 运行时 Skill 激活与当前群历史聊天检索的服务端入口。
     */
    private final ContextService contextService;
    /**
     * 每次 TaskRun 内检索工具的调用次数上限配置。
     */
    private final ContextSearchProperties contextSearchProperties;
    /**
     * 成功写后的预览回调（阶段 D），可空；Spring 注入 {@link CodingWriteObserver} 实现，
     * 未配置时 Coding 工具静默跳过，不影响编码流程。
     */
    private CodingWriteObserver writeObserver;

    public CodingAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       AgentProtocol protocol, ContextService contextService,
                       ContextSearchProperties contextSearchProperties) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.protocol = protocol;
        this.contextService = contextService;
        this.contextSearchProperties = contextSearchProperties;
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
        Set<String> observedChangedFiles = new LinkedHashSet<>();
        try {
            CodingResult coding = protocol.isNative()
                    ? executeCodingNative(input, observations, observedChangedFiles)
                    : executeCodingLegacy(input, observedChangedFiles);
            validateAndCompleteChanges(coding, observedChangedFiles);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setOutcome(coding.isSuccess() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED);
            outcome.setCodingResult(coding);
            outcome.setMessage(coding.isSuccess() ? coding.getSummary() : firstError(coding));
            outcome.setObservations(observations);
            log.info("coding agent done phase={} workspaceId={} outcome={} observations={}",
                    input.getPhase(), input.getWorkspaceId(), outcome.getOutcome(), observations.size());
            return outcome;
        } catch (CodingParseException e) {
            log.error("CODING_AGENT_FAILED phase={} workspaceId={} category={} code={} message={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName(), e.getCode(),
                    e.getMessage());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setFailureCode(e.getCode().name());
            failure.setMessage("coding agent failed: " + e.getMessage());
            failure.setObservations(observations);
            return failure;
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
    private CodingResult executeCodingNative(AgentInput input, List<LlmObservation> observations,
                                             Set<String> observedChangedFiles) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem(true);
        CodingTools tools = new CodingTools(input.getWorkspaceId(), codeAccess, writer);
        tools.setWriteObserver(trackingObserver(observedChangedFiles), input.getProjectId(), input.getTaskId(),
                input.getTaskRunId());
        ActivateSkillTool activateSkillTool = new ActivateSkillTool(contextService, input.getActorId(),
                input.getProjectId());
        ChatHistorySearchTool chatHistorySearchTool = new ChatHistorySearchTool(contextService, input.getActorId(),
                input.getProjectId(), input.getRequirementGroupId(), contextSearchProperties.getMaxPerRun());
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools, activateSkillTool, chatHistorySearchTool));
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
                try {
                    return parser.parse(turn.text());
                } catch (CodingParseException malformed) {
                    log.warn("coding agent final output not valid JSON, repairing phase={} workspaceId={} round={} code={}",
                            input.getPhase(), input.getWorkspaceId(), round, malformed.getCode());
                    String repaired = repairJson(system, turn.text(), observations, round, input);
                    if (repaired != null) {
                        return parser.parse(repaired);
                    }
                    throw malformed;
                }
            }
            throw new CodingParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "coding tool turn returned no text, history or infra failure");
        }
        throw new CodingParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    /**
     * 原生 Tool Calling 不能同时启用 response_format，因此最终文本仍可能出现未转义引号或围栏。
     * 解析失败时用一次无工具、强制 JSON_OBJECT 的补救调用重述原结果；不自行修补 JSON，避免
     * 改写模型语义。补救调用失败则保留原协议错误，交由状态机按基础设施失败重试。
     */
    private String repairJson(String system, String raw, List<LlmObservation> observations, int round,
                              AgentInput input) {
        String original = raw == null ? "" : raw;
        if (original.length() > 8_000) {
            original = original.substring(0, 8_000);
        }
        String repairUser = "你的上一轮最终输出不是合法 JSON。请仅输出一个原始 JSON 对象（不要输出任何解释、"
                + "代码围栏或多余内容），把上一轮结果整理为："
                + "{\"finalResult\":{\"success\":true|false,\"summary\":\"结果摘要\","
                + "\"modifiedFiles\":[\"相对路径\"],\"changes\":[\"变更说明\"],\"errors\":[\"错误说明\"]}}。\n\n"
                + "上一轮输出：\n" + original;
        try {
            String repaired = llm.complete(system, List.of(LlmMessage.user(repairUser)));
            String repairedSha = repaired == null ? null
                    : Sha256.hex(repaired.getBytes(StandardCharsets.UTF_8));
            observations.add(new LlmObservation(input.getPhase().name(), round + 1,
                    system.length() + repairUser.length(), repaired == null ? 0 : repaired.length(),
                    "stop", null, null, repairedSha));
            return repaired;
        } catch (RuntimeException e) {
            log.warn("coding agent JSON repair call failed phase={} workspaceId={} category={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * legacy 手写 JSON 协议循环：模型输出 toolCall/finalResult 文本，由 {@link CodingToolExecutor}
     * 执行工具。仅灰度期使用，协议切换稳定后删除。
     */
    private CodingResult executeCodingLegacy(AgentInput input, Set<String> observedChangedFiles) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent (legacy) workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem(false);
        CodingToolExecutor toolExecutor = new CodingToolExecutor(codeAccess, writer);
        toolExecutor.setWriteObserver(trackingObserver(observedChangedFiles), input.getProjectId(), input.getTaskId(),
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
     * Coding 的 success 不能只由模型自报决定：必须至少有一个成功且实际改变内容的写操作，
     * 或者由模型明确列出修改文件（兼容旧 Worker 未回传 changed 的历史实现）。实际写入路径
     * 会补入结果，避免模型遗漏 modifiedFiles；没有任何证据时把结果降为协议失败，防止
     * JSON repair 把“未执行任何文件修改”包装成 Developer 成功。
     */
    private void validateAndCompleteChanges(CodingResult coding, Set<String> observedChangedFiles) {
        if (coding == null || !coding.isSuccess()) {
            return;
        }
        List<String> declared = coding.getModifiedFiles();
        if (declared == null) {
            declared = List.of();
        }
        if (declared.isEmpty() && observedChangedFiles.isEmpty()) {
            throw new CodingParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "coding success requires at least one actual file modification");
        }
        if (declared.isEmpty() && !observedChangedFiles.isEmpty()) {
            coding.setModifiedFiles(new ArrayList<>(observedChangedFiles));
        }
    }

    /**
     * 记录本次 run 的真实变更，同时保留已有的 Diff 预览回调。回调异常不能影响 Coding 主循环。
     */
    private CodingWriteObserver trackingObserver(Set<String> observedChangedFiles) {
        return (projectId, taskId, taskRunId, workspaceId, result) -> {
            if (result != null && result.isOk() && result.isChanged() && result.getPath() != null) {
                observedChangedFiles.add(result.getPath());
            }
            if (writeObserver != null) {
                try {
                    writeObserver.onWrite(projectId, taskId, taskRunId, workspaceId, result);
                } catch (RuntimeException e) {
                    log.warn("CODING_WRITE_OBSERVER_FAILED path={} category={}",
                            result == null ? null : result.getPath(), e.getClass().getSimpleName());
                }
            }
        };
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
