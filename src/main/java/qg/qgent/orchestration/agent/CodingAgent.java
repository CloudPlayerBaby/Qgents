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
import qg.qgent.api.ApiException;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.tool.Sha256;
import qg.qgent.orchestration.tool.DevelopmentCommandPort;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.service.ContextService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实 Coding Agent：理解任务与计划，通过只读工具按需读取工作区代码，用 apply_patch
 * 精确修改已有文件、用 write_file 新建文件、用 create_directory 创建目录，真正修改工作区，并输出结构化
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
 * Git 或任意沙箱命令；仅允许调用固定模板的开发测试/构建命令。
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
    /**
     * 群聊图片/文件附件加载器：IMAGE 转 base64 媒体、文本文件内联，供多模态编码。
     */
    private final AttachmentMediaLoader attachmentMediaLoader;
    /** Coding Agent 的固定开发命令入口，不提供通用进程执行能力。 */
    private final DevelopmentCommandPort developmentCommands;

    public CodingAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       AgentProtocol protocol, ContextService contextService,
                       ContextSearchProperties contextSearchProperties,
                       AttachmentMediaLoader attachmentMediaLoader) {
        this(llm, codeAccess, writer, protocol, contextService, contextSearchProperties, attachmentMediaLoader,
                DevelopmentCommandPort.unavailable());
    }

    @Autowired
    public CodingAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                       AgentProtocol protocol, ContextService contextService,
                       ContextSearchProperties contextSearchProperties,
                       AttachmentMediaLoader attachmentMediaLoader,
                       DevelopmentCommandPort developmentCommands) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.protocol = protocol;
        this.contextService = contextService;
        this.contextSearchProperties = contextSearchProperties;
        this.attachmentMediaLoader = attachmentMediaLoader;
        this.developmentCommands = developmentCommands;
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
        log.info("coding agent input context {}", AgentContextLogFormatter.summary(input));
        if (log.isDebugEnabled()) {
            log.debug("coding agent input context samples taskId={} {}", input.getTaskId(),
                    AgentContextLogFormatter.samples(input));
        }
        List<LlmObservation> observations = new ArrayList<>();
        ChangedWriteFactLedger observedWrites = new ChangedWriteFactLedger(
                input.getRetryContext() == null ? null : input.getRetryContext().getPatchFailureCounts());
        try {
            CodingResult coding = protocol.isNative()
                    ? executeCodingNative(input, observations, observedWrites, null)
                    : executeCodingLegacy(input, observedWrites);
            validateAndCompleteChanges(coding, observedWrites, input);
            // "未尝试即放弃"有界纠正重试：模型自报失败但未调用任何工具、未产生任何写入时，
            // 以强化指令重跑一次（上限 1 次）。仍在同一 TaskRun 内完成，重试仍失败则保持
            // 原有 FAILED 终态语义，不改变状态机路由。
            boolean correctiveRetried = false;
            if (protocol.isNative() && shouldRetryGaveUp(coding, observations, observedWrites, input)) {
                log.info("CODING_GAVE_UP_RETRY phase={} workspaceId={} taskRunId={} "
                                + "首次运行未调用任何工具且未产生写入，执行一次纠正性重试",
                        input.getPhase(), input.getWorkspaceId(), input.getTaskRunId());
                correctiveRetried = true;
                coding = executeCodingNative(input, observations, observedWrites,
                        CodingPromptBuilder.correctiveGiveUpInstruction());
                validateAndCompleteChanges(coding, observedWrites, input);
            }
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            boolean patchUnrecoverable = !coding.isSuccess() && hasPatchRepairRequired(observedWrites);
            outcome.setOutcome(coding.isSuccess() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED);
            outcome.setHasRealChanges(observedWrites.hasChangedWrite());
            if (patchUnrecoverable) {
                outcome.setFailureCode(ProtocolFailureCode.TOOL_PATCH_UNRECOVERABLE.name());
            } else if (!coding.isSuccess() && outcome.getFailureCode() == null) {
                // 模型自报失败但无分类码（如未写文件、半途放弃）：显式标记为未分类失败，
                // 避免 TaskRun/Task 层把无码失败兜底成 FAILED_INFRASTRUCTURE（误导为基础设施故障）。
                outcome.setFailureCode("UNCLASSIFIED_FAILURE");
            }
            outcome.setCodingResult(coding);
            outcome.setMessage((coding.isSuccess() ? coding.getSummary() : firstError(coding))
                    + (patchUnrecoverable ? "；补丁连续失败且 replace_file 未完成，无法继续自动修复" : "")
                    + (correctiveRetried && !coding.isSuccess() ? "；已执行 1 次纠正性重试，模型仍未实际调用工具写入文件" : ""));
            outcome.setObservations(observations);
            outcome.setPatchFailureCounts(observedWrites.patchFailureCounts());
            log.info("coding agent done phase={} workspaceId={} outcome={} observations={}",
                    input.getPhase(), input.getWorkspaceId(), outcome.getOutcome(), observations.size());
            return outcome;
        } catch (CodingParseException e) {
            log.error("CODING_AGENT_FAILED phase={} workspaceId={} category={} code={} message={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName(), e.getCode(),
                    e.getMessage());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            // 无实际变更是本次 Coding 的语义失败。若按基础设施失败处理，状态机会
            // 重试同一个已达到目标状态的 Coding，产生 no-op 重试回环。
            boolean patchUnrecoverable = e.getCode() == ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE
                    && hasPatchRepairRequired(observedWrites);
            failure.setOutcome(e.getCode() == ProtocolFailureCode.CODING_NO_ACTUAL_CHANGE || patchUnrecoverable
                    ? RunOutcome.FAILED : RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setHasRealChanges(observedWrites.hasChangedWrite());
            failure.setFailureCode(patchUnrecoverable
                    ? ProtocolFailureCode.TOOL_PATCH_UNRECOVERABLE.name() : e.getCode().name());
            failure.setMessage("coding agent failed: " + e.getMessage()
                    + (patchUnrecoverable ? "；补丁连续失败且 replace_file 未完成，无法继续自动修复" : ""));
            failure.setObservations(observations);
            failure.setPatchFailureCounts(observedWrites.patchFailureCounts());
            return failure;
        } catch (ApiException e) {
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setHasRealChanges(observedWrites.hasChangedWrite());
            failure.setFailureCode(e.code());
            failure.setMessage("coding agent failed: " + e.getMessage());
            failure.setObservations(observations);
            failure.setPatchFailureCounts(observedWrites.patchFailureCounts());
            return failure;
        } catch (RuntimeException e) {
            log.error("CODING_AGENT_FAILED phase={} workspaceId={} category={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setHasRealChanges(observedWrites.hasChangedWrite());
            failure.setMessage("coding agent failed: " + e.getMessage());
            failure.setObservations(observations);
            failure.setPatchFailureCounts(observedWrites.patchFailureCounts());
            return failure;
        }
    }

    private boolean hasPatchRepairRequired(ChangedWriteFactLedger observedWrites) {
        String error = observedWrites.lastToolError();
        return error != null && error.contains("TOOL_PATCH_REPAIR_REQUIRED");
    }

    /**
     * "未尝试即放弃"判定：模型自报 success=false、本次 run 没有任何 changed=true 写入，
     * 且没有任何一轮观测到工具调用（含只读工具）。只有这类"完全没动手"才触发单次纠正重试；
     * 已调用过工具（哪怕失败）、已处于重试/质量回修链路、或模型直接声明成功（success=true，
     * 现一律放行）均不在此列，避免 no-op 重试回环。
     * <p>
     * 工具调用证据来自每轮 {@link LlmObservation#toolName()}：只有工具轮非空，
     * finalAnswer / repair / finalization 轮均为 null；本 run 无任何工具观测即"未动手"。
     */
    private boolean shouldRetryGaveUp(CodingResult coding, List<LlmObservation> observations,
                                      ChangedWriteFactLedger observedWrites, AgentInput input) {
        if (coding == null || coding.isSuccess()) {
            return false;
        }
        if (observedWrites.hasChangedWrite()) {
            return false;
        }
        if (input.getRetryContext() != null) {
            return false;
        }
        return observations.stream()
                .noneMatch(obs -> obs.toolName() != null && !obs.toolName().isBlank());
    }

    /**
     * 原生 Tool Calling 循环：每轮把历史（含 tool responses）回传给模型，直到输出 finalResult。
     * 每轮写入一条脱敏观测；工具执行遇到基础设施失败（Workspace 不可用）抛
     * {@link IllegalStateException}，由 run() 统一转为 FAILED_INFRASTRUCTURE。
     */
    private CodingResult executeCodingNative(AgentInput input, List<LlmObservation> observations,
                                             ChangedWriteFactLedger observedWrites, String correctiveInstruction) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        CodingTools tools = new CodingTools(input.getWorkspaceId(), codeAccess, writer, input.getAllowedPaths(),
                input.getRetryContext() == null ? null : input.getRetryContext().getPatchFailureCounts(),
                developmentCommands);
        tools.setWriteObserver(trackingObserver(observedWrites), input.getProjectId(), input.getTaskId(),
                input.getTaskRunId());
        ActivateSkillTool activateSkillTool = new ActivateSkillTool(contextService, input.getActorId(),
                input.getProjectId());
        String qualityRepairSkills = QualityRepairSkillContext.preloadAndRender(activateSkillTool,
                input.getRetryContext());
        List<Message> history = new ArrayList<>();
        history.add(buildUserMessage(input, files, qualityRepairSkills, correctiveInstruction));
        String system = promptBuilder.buildSystem(true, input.getAgentPrompt());
        ChatHistorySearchTool chatHistorySearchTool = new ChatHistorySearchTool(contextService, input.getActorId(),
                input.getProjectId(), input.getRequirementGroupId(), contextSearchProperties.getMaxPerRun());
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools, activateSkillTool, chatHistorySearchTool));
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> requestHistory = NativeToolLoopSupport.prepareToolRound(history, round,
                    observedWrites.changedPaths(), observedWrites.changedDirectories());
            Instant turnStartedAt = Instant.now();
            ToolTurnResult turn = llm.nextToolTurn(system, requestHistory, callbacks);
            observedWrites.recordToolFailure(tools.getLastToolError());
            observedWrites.recordToolOutcomes(tools.drainOutcomes());
            observations.add(LlmObservation.of(input.getPhase().name(), round, turn,
                    turnStartedAt, Instant.now()));
            if (turn.isInfraAbort()) {
                log.error("CODING_INFRA_ABORT phase={} workspaceId={} round={} tool={} reason={}",
                        input.getPhase(), input.getWorkspaceId(), round, turn.toolName(), turn.infraFailure());
                throw new IllegalStateException(turn.infraFailure());
            }
            if ("length".equalsIgnoreCase(turn.finishReason())) {
                return finalizeCoding(system, requestHistory, turn, observations, round, input.getPhase().name(),
                        ProtocolFailureCode.LLM_FINISH_LENGTH, observedWrites);
            }
            if (turn.continuesToolLoop()) {
                history = turn.history();
                if (round == MAX_TOOL_ROUNDS) {
                    return finalizeCoding(system, requestHistory, turn, observations, round, input.getPhase().name(),
                            ProtocolFailureCode.LLM_CONTEXT_LIMIT, observedWrites);
                }
                continue;
            }
            if (turn.isFinalText()) {
                log.info("coding agent round {} finalResult phase={} workspaceId={}",
                        round, input.getPhase(), input.getWorkspaceId());
                try {
                    return parser.parse(turn.text());
                } catch (CodingParseException malformed) {
                    log.warn("coding agent final output not valid JSON, repairing phase={} workspaceId={} round={} code={}",
                            input.getPhase(), input.getWorkspaceId(), round, malformed.getCode());
                    String repaired = repairJson(system, turn.text(), malformed.getMessage(), observations, round, input);
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
     * 构建 Coding 首轮 UserMessage：正文（promptBuilder）+ 群聊中的图片媒体（IMAGE 附件，
     * base64 data URI）+ 文本/代码附件内容块（FILE，TEXT/CODE 且 ≤64KB）。
     * <p>
     * 按 {@link AttachmentMediaLoader} 先裁剪类型与大小，避免把超大附件读入内存。
     * 读取失败、越权、越预算或类型不支持的附件降级为文本引用（ContextPromptRenderer 已渲染
     * [图片附件]/[文件附件]），不影响编码主流程与成功收敛。
     */
    private UserMessage buildUserMessage(AgentInput input, List<String> files, String qualityRepairSkills,
                                         String correctiveInstruction) {
        String text = promptBuilder.buildUser(input, files);
        AttachmentMediaLoader.Result attachments =
                attachmentMediaLoader.load(input.getActorId(), input.getProjectId(), input.getConversation());
        String finalText = attachments.extraText().isEmpty() ? text : text + attachments.extraText();
        if (qualityRepairSkills != null && !qualityRepairSkills.isBlank()) {
            finalText += qualityRepairSkills;
        }
        if (correctiveInstruction != null && !correctiveInstruction.isBlank()) {
            finalText += correctiveInstruction;
        }
        UserMessage.Builder builder = UserMessage.builder().text(finalText);
        if (!attachments.media().isEmpty()) {
            builder.media(attachments.media());
        }
        return builder.build();
    }

    private CodingResult finalizeCoding(String system, List<Message> requestHistory, ToolTurnResult trigger,
                                        List<LlmObservation> observations, int round,
                                        String phase, ProtocolFailureCode triggerCode,
                                        ChangedWriteFactLedger observedWrites) {
        Instant finalizationStartedAt = Instant.now();
        ToolTurnResult finalization = llm.finalizeToolTurn(system,
                NativeToolLoopSupport.prepareFinalization(requestHistory, trigger),
                NativeToolLoopSupport.finalizationInstruction(
                        "{\"finalResult\":{\"success\":true|false,\"summary\":\"结果摘要\","
                                + "\"modifiedFiles\":[\"相对路径\"],\"modifiedDirectories\":[\"相对目录\"],\"changes\":[\"变更说明\"],"
                                + "\"errors\":[\"错误说明\"]}}", observedWrites.changedPaths(),
                        observedWrites.changedDirectories()));
        observations.add(LlmObservation.of(phase, round + 1, finalization,
                finalizationStartedAt, Instant.now()));
        if (!finalization.isFinalText() || "length".equalsIgnoreCase(finalization.finishReason())) {
            throw new CodingParseException(triggerCode, "bounded coding finalization did not produce complete JSON");
        }
        return parser.parse(finalization.text());
    }

    /**
     * 原生 Tool Calling 不能同时启用 response_format，因此最终文本仍可能出现未转义引号或围栏。
     * 解析失败时用一次无工具、强制 JSON_OBJECT 的补救调用重述原结果；不自行修补 JSON，避免
     * 改写模型语义。补救调用失败则保留原协议错误，交由状态机按基础设施失败重试。
     */
    private String repairJson(String system, String raw, String errorMessage, List<LlmObservation> observations, int round,
                              AgentInput input) {
        String repairUser = JsonRepairSupport.buildPrompt(raw, errorMessage,
                "{\"finalResult\":{\"success\":true|false,\"summary\":\"结果摘要\","
                        + "\"modifiedFiles\":[\"相对路径\"],\"modifiedDirectories\":[\"相对目录\"],\"changes\":[\"变更说明\"],\"errors\":[\"错误说明\"]}}" );
        String repaired = JsonRepairSupport.repairOnce(llm, system, raw, errorMessage,
                "{\"finalResult\":{\"success\":true|false,\"summary\":\"结果摘要\","
                        + "\"modifiedFiles\":[\"相对路径\"],\"modifiedDirectories\":[\"相对目录\"],\"changes\":[\"变更说明\"],\"errors\":[\"错误说明\"]}}");
        String repairedSha = repaired == null ? null
                : Sha256.hex(repaired.getBytes(StandardCharsets.UTF_8));
        observations.add(new LlmObservation(input.getPhase().name(), round + 1,
                system.length() + repairUser.length(), repaired == null ? 0 : repaired.length(),
                "stop", null, null, repairedSha));
        return repaired;
    }

    /**
     * legacy 手写 JSON 协议循环：模型输出 toolCall/finalResult 文本，由 {@link CodingToolExecutor}
     * 执行工具。仅灰度期使用，协议切换稳定后删除。
     */
    private CodingResult executeCodingLegacy(AgentInput input, ChangedWriteFactLedger observedWrites) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent (legacy) workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem(false, input.getAgentPrompt());
        CodingToolExecutor toolExecutor = new CodingToolExecutor(codeAccess, writer, input.getAllowedPaths(),
                input.getRetryContext() == null ? null : input.getRetryContext().getPatchFailureCounts());
        toolExecutor.setWriteObserver(trackingObserver(observedWrites), input.getProjectId(), input.getTaskId(),
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
                observedWrites.recordToolFailure(toolExecutor.getLastToolError());
                observedWrites.recordPatchFailureCounts(toolExecutor.getPatchFailureCounts());
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
            return JsonTextExtractor.parseObject(objectMapper, raw);
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
     * Coding 的 success 以模型声明的 success 为准；本方法只负责把结果范围修正为服务端观察到的
     * 真实 changed=true 写入事实，避免模型遗漏或编造 modifiedFiles/modifiedDirectories。
     * <p>
     * 当前为临时放宽（全部放行）：模型声明 success 但本次零写入时不再判
     * CODING_NO_ACTUAL_CHANGE，而是按 SUCCEEDED 收敛，并把本步 modifiedFiles/modifiedDirectories
     * 置空——最终 Diff 仍由工作区真实 git diff 生成，不虚报本次写入路径。原因：绿地/重试任务频繁
     * 因"工作已在工作区、本轮无新增写入"被误判失败而整条任务跑不通；放宽后模型编造的路径不会进入
     * 下游，但"虚报成功（成功却零产出）"不再被硬检查拦截。如需恢复严格语义，改回此方法抛出
     * {@link ProtocolFailureCode#CODING_NO_ACTUAL_CHANGE} 即可。
     */
    private void validateAndCompleteChanges(CodingResult coding, ChangedWriteFactLedger observedWrites,
                                            AgentInput input) {
        if (coding == null || !coding.isSuccess()) {
            return;
        }
        if (!observedWrites.hasChangedWrite()) {
            log.warn("CODING_SUCCESS_WITHOUT_WRITES phase={} workspaceId={} targets={} attempts={}",
                    input.getPhase(), input.getWorkspaceId(), input.getTargetFiles(),
                    observedWrites.toolOutcomeSummary());
            // 本步无真实变更：modifiedFiles/Directories 置空，避免模型编造路径污染 Review/Test 上下文。
            coding.setModifiedFiles(List.of());
            coding.setModifiedDirectories(List.of());
            return;
        }
        // 结果范围只使用服务端观察到的真实 changed=true 事实，避免模型伪造路径污染 Review/Test 上下文。
        coding.setModifiedFiles(new ArrayList<>(observedWrites.changedPaths()));
        coding.setModifiedDirectories(new ArrayList<>(observedWrites.changedDirectories()));
    }

    /**
     * 记录本次 run 的真实变更，同时保留已有的 Diff 预览回调。回调异常不能影响 Coding 主循环。
     */
    private CodingWriteObserver trackingObserver(ChangedWriteFactLedger observedWrites) {
        CodingWriteObserver delegate = (projectId, taskId, taskRunId, workspaceId, result) -> {
            if (writeObserver != null) {
                try {
                    writeObserver.onWrite(projectId, taskId, taskRunId, workspaceId, result);
                } catch (RuntimeException e) {
                    log.warn("CODING_WRITE_OBSERVER_FAILED path={} category={}",
                            result == null ? null : result.getPath(), e.getClass().getSimpleName());
                }
            }
        };
        return observedWrites.observing(delegate);
    }

}
