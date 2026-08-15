package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实 Review Agent：独立审查 Coding Agent 的实际修改是否实现了 Task 与 Plan 的目标，
 * 结合预取的 Git Diff 与只读代码访问核实问题，输出结构化 {@link ReviewResult}。
 * <p>
 * 权限与真实性约束：
 * <ul>
 *   <li>构造器只接收 {@link WorkspaceCodeAccess} 与 {@link WorkspaceDiffAccess}，不持有任何写端口，
 *       工具执行器 {@link ReviewToolExecutor} 也刻意拒绝 write_file，从结构上保证只能读不能写；</li>
 *   <li>git_diff 由 {@link WorkspaceDiffAccess} 预取并嵌入初始上下文，审查循环只暴露
 *       list_files/read_file/search_code；diff 不可用（未就绪）→ FAILED_INFRASTRUCTURE；</li>
 *   <li>success 的最终判定依据 severity 策略：存在 BLOCKER/MAJOR 时强制 FAIL，
 *       只有 MINOR/INFO 时方可采纳 LLM 的 success；不得只凭 LLM 声称通过；</li>
 *   <li>输出非法、缺必填字段、severity 非法、超循环上限等抛 {@link ReviewParseException}，
 *       统一转为 FAILED_INFRASTRUCTURE 同相位重试，不把非法输出当作真实审查结论。</li>
 * </ul>
 * 不修改 Workspace、不 write_file、不调用其他 Agent、不执行 Git commit/push/MR、不访问宿主机。
 */
@Component
public class ReviewAgent implements Agent {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceDiffAccess diffAccess;
    private final ReviewPromptBuilder promptBuilder = new ReviewPromptBuilder();
    private final ReviewResultParser parser = new ReviewResultParser();
    private final ReviewToolExecutor toolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceDiffAccess diffAccess) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.diffAccess = diffAccess;
        this.toolExecutor = new ReviewToolExecutor(codeAccess);
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        try {
            GitDiffResult diff = diffAccess.diff(input.getWorkspaceId());
            if (!diff.ok()) {
                return infraFailure(input, "git diff unavailable: " + diff.error());
            }
            ReviewResult review = executeReview(input, diff);
            boolean blockerOrMajor = hasBlockerOrMajor(review);
            boolean success = !blockerOrMajor && review.isSuccess();
            review.setSuccess(success);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setReviewResult(review);
            outcome.setOutcome(success ? RunOutcome.SUCCEEDED
                    : (review.isNeedsCodingFix() ? RunOutcome.FAILED_QUALITY : RunOutcome.FAILED));
            outcome.setMessage(success ? review.getSummary() : firstFinding(review));
            return outcome;
        } catch (RuntimeException e) {
            return infraFailure(input, e.getMessage());
        }
    }

    /**
     * 多轮只读工具调用循环：工具结果持续回灌上下文，直到模型输出 finalResult 或达到上限。
     */
    private ReviewResult executeReview(AgentInput input, GitDiffResult diff) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files, diff)));
        String system = promptBuilder.buildSystem();
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
            throw new ReviewParseException("review output is neither toolCall nor finalResult");
        }
        throw new ReviewParseException("exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
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
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new ReviewParseException("review output is not valid JSON: " + e.getMessage());
        }
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

    private AgentRunOutcome infraFailure(AgentInput input, String message) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(input.getPhase());
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setMessage("review agent failed: " + message);
        return failure;
    }
}
