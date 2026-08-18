package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentDispatcher;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmOutputTruncatedException;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实 Plan Agent：理解任务、通过只读工具分析 Workspace 代码并产出结构化 {@link PlanResult}。
 * <p>
 * 两轮按需读取：① 依据文件树让 LLM 挑选要读取的文件（JSON readRequests）；
 * ② 携带文件树与选中文件内容调用 LLM 生成实现计划，经 {@link PlanResultParser}
 * 校验后返回。绝不修改、创建、删除文件，不执行 Git 操作，不调用其他 Agent——
 * 上述限制由 {@code WorkspaceCodeAccess} 只读接口在结构上强制保证。
 * <p>
 * 联合规划：规划前经 {@link AgentDispatcher#listTeamCandidates} 拉取团队候选 Agent 池
 * （ACTIVE + 对任务创建者可见，不限角色）注入提示词，让 Plan 拆步骤时考虑可用 Agent
 * 的职责分工，并在每个 step 输出可选的 {@code suggestedAgentId} 作为选人先验；候选池
 * 查询失败/为空时降级为纯业务规划（空池），不阻断编排——池内校验仍在物化选人时兜底。
 * <p>
 * 任何异常（LLM 调用、响应非法或不完整）统一转为 FAILED_INFRASTRUCTURE，
 * 由 Orchestrator 状态机决定同相位重试，避免破坏链路推进。
 */
@Component
public class PlanAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    private static final int MAX_READ_FILES = 8;
    private static final int MAX_READ_REQUESTS = 8;

    private final LlmClient llm;
    private final WorkspaceCodeAccess codeAccess;
    private final AgentDispatcher agentDispatcher;
    private final PlanPromptBuilder promptBuilder = new PlanPromptBuilder();
    private final PlanResultParser parser = new PlanResultParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanAgent(LlmClient llm, WorkspaceCodeAccess codeAccess, AgentDispatcher agentDispatcher) {
        this.llm = llm;
        this.codeAccess = codeAccess;
        this.agentDispatcher = agentDispatcher;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        try {
            List<String> files = codeAccess.listFiles(input.getWorkspaceId());
            List<String> toRead = selectFilesToRead(input, files);
            Map<String, String> contents = readSelectedFiles(input, toRead);
            List<AgentEntity> pool = loadCandidatePool(input);
            String planSystem = promptBuilder.buildPlanSystem();
            String planJson = llm.complete(planSystem,
                    promptBuilder.buildPlanUser(input, files, contents, pool));
            PlanResult plan;
            try {
                plan = parser.parse(planJson);
            } catch (PlanParseException malformed) {
                log.warn("plan agent final output not valid JSON, repairing code={}", malformed.getMessage());
                String repaired = JsonRepairSupport.repairOnce(llm, planSystem, planJson, malformed.getMessage(),
                        "{\"taskUnderstanding\":\"...\",\"implementationGoals\":[\"...\"],"
                                + "\"steps\":[{\"title\":\"...\",\"files\":[\"relative/path\"],"
                                + "\"description\":\"...\"}],\"testPlan\":\"...\","
                                + "\"verificationMode\":\"AUTOMATED|MANUAL\",\"risks\":[\"...\"],"
                                + "\"deliveryMode\":\"DIFF_FIRST|MR_FIRST\",\"scaleReason\":\"...\"}");
                if (repaired == null) {
                    throw malformed;
                }
                plan = parser.parse(repaired);
            }

            AgentRunOutcome outcome = new AgentRunOutcome();
            outcome.setPhase(input.getPhase());
            outcome.setOutcome(RunOutcome.SUCCEEDED);
            outcome.setPlanResult(plan);
            outcome.setMessage("plan ready");
            return outcome;
        } catch (PlanParseException e) {
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setFailureCode(e.getCode().name());
            failure.setMessage("plan agent failed: " + e.getMessage());
            return failure;
        } catch (LlmOutputTruncatedException e) {
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setFailureCode(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
            failure.setMessage("plan agent failed: " + e.getMessage());
            return failure;
        } catch (RuntimeException e) {
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(input.getPhase());
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setMessage("plan agent failed: " + e.getMessage());
            return failure;
        }
    }

    /**
     * 规划期候选池快照：查询失败或返回 null 时降级为空池（纯业务规划），不阻断编排。
     */
    private List<AgentEntity> loadCandidatePool(AgentInput input) {
        try {
            List<AgentEntity> pool = agentDispatcher.listTeamCandidates(input.getProjectId(), input.getActorId());
            return pool == null ? List.of() : pool;
        } catch (RuntimeException e) {
            log.warn("plan agent pool load failed, degrade to planning without agent pool: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 第一轮：让 LLM 从文件树挑选要读取的文件；解析失败时退回不读取任何文件。
     */
    private List<String> selectFilesToRead(AgentInput input, List<String> files) {
        try {
            String raw = llm.complete(promptBuilder.buildSelectFilesSystem(),
                    promptBuilder.buildSelectFilesUser(input, files));
            JsonNode node = JsonTextExtractor.parseObject(objectMapper, raw);
            JsonNode requests = node.get("readRequests");
            List<String> selected = new ArrayList<>();
            if (requests != null && requests.isArray()) {
                for (JsonNode item : requests) {
                    if (item.isTextual() && !item.asText().isBlank() && selected.size() < MAX_READ_REQUESTS) {
                        selected.add(item.asText().trim());
                    }
                }
            }
            return selected;
        } catch (Exception e) {
            // 读取选择解析失败时退回不读取任何文件，不阻塞计划生成。
            return List.of();
        }
    }

    /**
     * 按需读取选中的文件；目录缺失、越界或文件过大时跳过该文件。
     */
    private Map<String, String> readSelectedFiles(AgentInput input, List<String> paths) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (String path : paths) {
            if (contents.size() >= MAX_READ_FILES) {
                break;
            }
            WorkspaceFileReadResult read = codeAccess.readFile(input.getWorkspaceId(), path);
            if (read != null && read.isOk()) {
                contents.put(path, read.getContent());
            }
        }
        return contents;
    }
}
