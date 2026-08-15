package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实 Coding Agent：理解任务与计划，通过只读工具按需读取工作区代码，用 write_file
 * 真正修改工作区文件，并输出结构化 finalResult 生成 {@link CodingResult}。
 * <p>
 * 采用结构化 JSON 工具调用协议：模型输出 {@code {"toolCall":{name,arguments}}} 时执行
 * 工具并把结果以 TOOL 消息回灌历史继续决策；输出 {@code {"finalResult":{...}}} 时收敛
 * 结束。循环上限 {@link #MAX_TOOL_ROUNDS}，避免模型无限调用工具。
 * <p>
 * 结果分类：合法 finalResult 按 success 映射 SUCCEEDED / FAILED；输出非法、缺必填字段、
 * 超循环上限等协议失败抛 {@link CodingParseException}，统一转为 FAILED_INFRASTRUCTURE，
 * 由状态机决定同相位重试。只经 {@link WorkspaceCodeWriter} 写工作区，不执行 Git 或沙箱命令。
 */
@Slf4j
@Component
public class CodingAgent implements Agent {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final CodingPromptBuilder promptBuilder = new CodingPromptBuilder();
    private final CodingResultParser parser = new CodingResultParser();
    private final CodingToolExecutor toolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodingAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.toolExecutor = new CodingToolExecutor(codeAccess, writer);
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        log.info("coding agent start phase={} workspaceId={}", input.getPhase(), input.getWorkspaceId());
        try {
            CodingResult coding = executeCoding(input);
            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setOutcome(coding.isSuccess() ? RunOutcome.SUCCEEDED : RunOutcome.FAILED);
            outcome.setCodingResult(coding);
            outcome.setMessage(coding.isSuccess() ? coding.getSummary() : firstError(coding));
            log.info("coding agent done phase={} workspaceId={} outcome={}",
                    input.getPhase(), input.getWorkspaceId(), outcome.getOutcome());
            return outcome;
        } catch (RuntimeException e) {
            log.error("CODING_AGENT_FAILED phase={} workspaceId={} category={}",
                    input.getPhase(), input.getWorkspaceId(), e.getClass().getSimpleName());
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setMessage("coding agent failed: " + e.getMessage());
            return failure;
        }
    }

    /**
     * 多轮工具调用循环：工具结果持续回灌上下文，直到模型输出 finalResult 或达到上限。
     */
    private CodingResult executeCoding(AgentInput input) {
        List<String> files = codeAccess.listFiles(input.getWorkspaceId());
        log.info("coding agent workspace files phase={} workspaceId={} files={}",
                input.getPhase(), input.getWorkspaceId(), files.size());
        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(promptBuilder.buildUser(input, files)));
        String system = promptBuilder.buildSystem();
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
            throw new CodingParseException("coding output is neither toolCall nor finalResult");
        }
        throw new CodingParseException("exceeded " + MAX_TOOL_ROUNDS + " tool rounds without a final result");
    }

    private JsonNode toJson(String raw) {
        try {
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            throw new CodingParseException("coding output is not valid JSON: " + e.getMessage());
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
