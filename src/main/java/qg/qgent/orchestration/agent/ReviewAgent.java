package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.Sha256;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.ContextService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实 Review Agent：独立审查 Coding Agent 的实际修改是否实现了 Task 与 Plan 的目标，
 * 结合预取的 Git Diff 与只读代码访问核实问题，输出结构化 {@link ReviewResult}。
 * <p>
 * 权限与真实性约束：
 * <ul>
 *   <li>构造器只接收 {@link WorkspaceCodeAccess} 与 {@link WorkspaceDiffAccess}，不持有任何写端口，
 *       原生工具 {@link ReviewTools} 与 legacy 执行器 {@link ReviewToolExecutor} 也刻意拒绝
 *       write_file，从结构上保证只能读不能写；</li>
 *   <li>git_diff 由 {@link WorkspaceDiffAccess} 预取并嵌入初始上下文，审查循环只暴露
 *       list_files/read_file/search_code；diff 不可用（未就绪）→ FAILED_INFRASTRUCTURE；</li>
 *   <li>success 的最终判定依据 severity 策略：存在 BLOCKER/MAJOR 时强制 FAIL，
 *       只有 MINOR/INFO 时方可采纳 LLM 的 success；不得只凭 LLM 声称通过；</li>
 *   <li>阶段 B 起默认走原生 Tool Calling（{@code app.agent.protocol=native}），legacy 手写 JSON
 *       协议仅灰度期保留，协议稳定后删除；输出非法、缺必填字段、severity 非法、超循环上限等抛
 *       {@link ReviewParseException}，统一转为 FAILED_INFRASTRUCTURE 同相位重试。</li>
 * </ul>
 * 每轮模型调用生成一条脱敏观测 {@link LlmObservation} 随 Run 产物落库。
 * 不修改 Workspace、不 write_file、不调用其他 Agent、不执行 Git commit/push/MR、不访问宿主机。
 */
@Slf4j
@Component
public class ReviewAgent implements Agent {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceDiffAccess diffAccess;
    private final AgentProtocol protocol;
    private final ReviewPromptBuilder promptBuilder = new ReviewPromptBuilder();
    private final ReviewResultParser parser = new ReviewResultParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 运行时 Skill 激活与当前群历史聊天检索的服务端入口。
     */
    private final ContextService contextService;
    /**
     * 每次 TaskRun 内检索工具的调用次数上限配置。
     */
    private final ContextSearchProperties contextSearchProperties;

    public ReviewAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceDiffAccess diffAccess,
                       AgentProtocol protocol, ContextService contextService,
                       ContextSearchProperties contextSearchProperties) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.diffAccess = diffAccess;
        this.protocol = protocol;
        this.contextService = contextService;
        this.contextSearchProperties = contextSearchProperties;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        log.info("review agent start phase={} workspaceId={} protocol={}",
                input.getPhase(), input.getWorkspaceId(), protocol.isNative() ? "native" : "legacy");
        List<LlmObservation> observations = new ArrayList<>();
        try {
            GitDiffResult diff = diffAccess.diff(input.getWorkspaceId());
            if (!diff.ok()) {
                log.warn("REVIEW_DIFF_UNAVAILABLE phase={} workspaceId={}",
                        input.getPhase(), input.getWorkspaceId());
                return infraFailure(input, "git diff unavailable: " + diff.error(), observations);
            }
            ReviewResult review = protocol.isNative()
                    ? executeReviewNative(input, diff, observations)
                    : executeReviewLegacy(input, diff);
            boolean blockerOrMajor = hasBlockerOrMajor(review);
            boolean success = !blockerOrMajor && review.isSuccess();
            review.setSuccess(success);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setReviewResult(review);
            outcome.setOutcome(success ? RunOutcome.SUCCEEDED
                    : (review.isNeedsCodingFix() ? RunOutcome.FAILED_QUALITY : RunOutcome.FAILED));
            outcome.setMessage(success ? review.getSummary() : firstFinding(review));
            outcome.setObservations(observations);
            log.info("review agent done phase={} workspaceId={} outcome={} observations={}",
                    input.getPhase(), input.getWorkspaceId(), outcome.getOutcome(), observations.size());
            return outcome;
        } catch (RuntimeException e) {
            log.error("REVIEW_AGENT_FAILED phase={} workspaceId={} category={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName());
            String failureCode = e instanceof ReviewParseException parse
                    ? parse.getCode().name() : null;
            return infraFailure(input, e.getMessage(), observations, failureCode);
        }
    }

    /**
     * 原生 Tool Calling 只读循环：每轮把历史回传给模型，直到输出 finalResult；每轮写入观测。
     */
    private ReviewResult executeReviewNative(AgentInput input, GitDiffResult diff,
                                             List<LlmObservation> observations) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(promptBuilder.buildUser(input, files, diff)));
        String system = promptBuilder.buildSystem(true);
        ReviewTools tools = new ReviewTools(input.getWorkspaceId(), codeAccess);
        ActivateSkillTool activateSkillTool = new ActivateSkillTool(contextService, input.getActorId(),
                input.getProjectId());
        ChatHistorySearchTool chatHistorySearchTool = new ChatHistorySearchTool(contextService, input.getActorId(),
                input.getProjectId(), input.getRequirementGroupId(), contextSearchProperties.getMaxPerRun());
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools, activateSkillTool, chatHistorySearchTool));
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> requestHistory = NativeToolLoopSupport.prepareToolRound(history, round);
            ToolTurnResult turn = llm.nextToolTurn(system, requestHistory, callbacks);
            observations.add(LlmObservation.of(input.getPhase().name(), round, turn));
            if (turn.isInfraAbort()) {
                log.error("REVIEW_INFRA_ABORT phase={} workspaceId={} round={} tool={} reason={}",
                        input.getPhase(), input.getWorkspaceId(), round, turn.toolName(), turn.infraFailure());
                throw new IllegalStateException(turn.infraFailure());
            }
            if ("length".equalsIgnoreCase(turn.finishReason())) {
                return finalizeReview(system, requestHistory, turn, observations, round, input.getPhase().name(),
                        ProtocolFailureCode.LLM_FINISH_LENGTH);
            }
            if (turn.continuesToolLoop()) {
                history = turn.history();
                if (round == MAX_TOOL_ROUNDS) {
                    return finalizeReview(system, requestHistory, turn, observations, round, input.getPhase().name(),
                            ProtocolFailureCode.LLM_CONTEXT_LIMIT);
                }
                continue;
            }
            if (turn.isFinalText()) {
                log.info("review agent round {} finalResult phase={} workspaceId={}",
                        round, input.getPhase(), input.getWorkspaceId());
                try {
                    return parser.parse(turn.text());
                } catch (ReviewParseException malformed) {
                    log.warn("review agent final output not valid JSON, repairing phase={} workspaceId={} round={} code={}",
                            input.getPhase(), input.getWorkspaceId(), round, malformed.getCode());
                    String repaired = repairJson(system, turn.text(), malformed.getMessage(), observations, round, input);
                    if (repaired != null) {
                        return parser.parse(repaired);
                    }
                    throw malformed;
                }
            }
            throw new ReviewParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "review tool turn returned no text, history or infra failure");
        }
        throw new ReviewParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    private ReviewResult finalizeReview(String system, List<Message> requestHistory, ToolTurnResult trigger,
                                        List<LlmObservation> observations, int round,
                                        String phase, ProtocolFailureCode triggerCode) {
        ToolTurnResult finalization = llm.finalizeToolTurn(system,
                NativeToolLoopSupport.prepareFinalization(requestHistory, trigger),
                NativeToolLoopSupport.finalizationInstruction(
                        "{\"success\":true|false,\"summary\":\"审查摘要\","
                                + "\"findings\":[{\"file\":\"相对路径\",\"line\":1,"
                                + "\"severity\":\"BLOCKER|MAJOR|MINOR|INFO\",\"issue\":\"问题\","
                                + "\"suggestion\":\"建议\"}],\"suggestions\":[\"建议\"],"
                                + "\"needsCodingFix\":true|false}"));
        observations.add(LlmObservation.of(phase, round + 1, finalization));
        if (!finalization.isFinalText() || "length".equalsIgnoreCase(finalization.finishReason())) {
            throw new ReviewParseException(triggerCode, "bounded review finalization did not produce complete JSON");
        }
        return parser.parse(finalization.text());
    }

    /**
     * 原生 Tool Calling 的最终文本不能同时依赖 response_format；模型偶尔会返回普通说明。
     * 解析失败时最多调用一次无工具 repair，只要求模型把原结果重述为 ReviewResult JSON，
     * 不在服务端自行猜测或修补审查语义。repair 失败时保留原协议错误，交由编排器重试。
     */
    private String repairJson(String system, String raw, String errorMessage, List<LlmObservation> observations, int round,
                              AgentInput input) {
        String repairUser = JsonRepairSupport.buildPrompt(raw, errorMessage,
                "{\"success\":true|false,\"summary\":\"审查摘要\","
                        + "\"findings\":[{\"file\":\"相对路径\",\"line\":1,\"severity\":\"BLOCKER|MAJOR|MINOR|INFO\","
                        + "\"issue\":\"问题\",\"suggestion\":\"建议\"}],"
                        + "\"suggestions\":[\"建议\"],\"needsCodingFix\":true|false}" );
        String repaired = JsonRepairSupport.repairOnce(llm, system, raw, errorMessage,
                "{\"success\":true|false,\"summary\":\"审查摘要\","
                        + "\"findings\":[{\"file\":\"相对路径\",\"line\":1,\"severity\":\"BLOCKER|MAJOR|MINOR|INFO\","
                        + "\"issue\":\"问题\",\"suggestion\":\"建议\"}],"
                        + "\"suggestions\":[\"建议\"],\"needsCodingFix\":true|false}");
        String repairedSha = repaired == null ? null
                : Sha256.hex(repaired.getBytes(StandardCharsets.UTF_8));
        observations.add(new LlmObservation(input.getPhase().name(), round + 1,
                system.length() + repairUser.length(), repaired == null ? 0 : repaired.length(),
                "stop", null, null, repairedSha));
        return repaired;
    }

    /**
     * legacy 手写 JSON 协议只读循环：模型输出 toolCall/finalResult 文本，由
     * {@link ReviewToolExecutor} 执行只读工具。仅灰度期使用，协议稳定后删除。
     */
    private ReviewResult executeReviewLegacy(AgentInput input, GitDiffResult diff) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files, diff)));
        String system = promptBuilder.buildSystem(false);
        ReviewToolExecutor toolExecutor = new ReviewToolExecutor(codeAccess);
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            String raw = llm.complete(system, history);
            history.add(LlmMessage.assistant(raw));
            JsonNode node = toJson(raw);
            JsonNode toolCall = node.get("toolCall");
            if (toolCall != null && toolCall.isObject()) {
                history.add(LlmMessage.tool(toolExecutor.execute(input.getWorkspaceId(), toolCall)));
                continue;
            }
            JsonNode finalResult = node.get("finalResult");
            if (finalResult != null && finalResult.isObject()) {
                return parser.parse(finalResult);
            }
            throw new ReviewParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "review output is neither toolCall nor finalResult");
        }
        throw new ReviewParseException(ProtocolFailureCode.LLM_CONTEXT_LIMIT,
                "exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    /**
     * 依据 severity 策略的确定性底线：存在 BLOCKER/MAJOR 即强制 FAIL，即使 LLM 声称通过。
     */
    private boolean hasBlockerOrMajor(ReviewResult review) {
        if (review.getFindings() == null) {
            return false;
        }
        for (ReviewResult.Finding finding : review.getFindings()) {
            String severity = finding.getSeverity() == null ? "" : finding.getSeverity().toUpperCase();
            if ("BLOCKER".equals(severity) || "MAJOR".equals(severity)) {
                return true;
            }
        }
        return false;
    }

    private String firstFinding(ReviewResult review) {
        if (review.getFindings() != null && !review.getFindings().isEmpty()) {
            ReviewResult.Finding finding = review.getFindings().get(0);
            String severity = finding.getSeverity() == null ? "" : finding.getSeverity();
            String issue = finding.getIssue() == null ? "" : finding.getIssue();
            return (severity + " " + issue).trim();
        }
        return review.getSummary() == null ? "review failed" : review.getSummary();
    }

    private JsonNode toJson(String raw) {
        try {
            return JsonTextExtractor.parseObject(objectMapper, raw);
        } catch (Exception e) {
            throw new ReviewParseException(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED,
                    "review output is not valid JSON: " + e.getMessage());
        }
    }

    private AgentRunOutcome infraFailure(AgentInput input, String message, List<LlmObservation> observations) {
        return infraFailure(input, message, observations, null);
    }

    private AgentRunOutcome infraFailure(AgentInput input, String message, List<LlmObservation> observations,
                                         String failureCode) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(input.getPhase());
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setFailureCode(failureCode);
        failure.setMessage("review agent failed: " + message);
        failure.setObservations(observations);
        return failure;
    }
}
