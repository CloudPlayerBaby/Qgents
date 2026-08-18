package qg.qgent.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.llm.LlmClient;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 选用决策器：把「步骤角色 + 候选 Agent 的 role/description」丢给决策 Agent（LLM）
 * 判断谁最适合完成该步骤角色的工作，不再按结构化能力标签打分量化。
 * <p>
 * 决策规则：
 * <ol>
 *   <li>候选为空 → 空（不硬选）；</li>
 *   <li>Plan 建议（{@code suggestedAgentId}）非空且在候选池内 → 直接采用（先验短路，不重复调 LLM）；
 *       建议非空但不在候选池（角色不匹配 / 已下线 / 越权）→ 记日志忽略，继续走常规决策；</li>
 *   <li>候选唯一 → 直接返回（省一次 LLM 调用）；</li>
 *   <li>候选多于一个时，用 {@link LlmClient#complete} 询问决策 Agent：给定步骤角色与候选
 *       （id/name/description），输出最合适的 Agent id（或 NONE）；</li>
 *   <li>决策 Agent 返回的 id 必须在候选池内，否则不采信（绝不引入池外 Agent）；</li>
 *   <li>LLM 调用失败 / 输出无法解析 / 返回 NONE → 确定性兜底：任务创建者本人的 PRIVATE
 *       Agent 优先，其次按名称升序取第一个，保证任务不因选择失败而挂起。</li>
 * </ol>
 * 纯逻辑层：不做代理、不落库，LLM 失败只降级不抛异常。
 */
@Service
public class AgentMatchDecider {

    private static final Logger log = LoggerFactory.getLogger(AgentMatchDecider.class);

    private final LlmClient llm;

    public AgentMatchDecider(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * 从候选池中选出最适合步骤角色的 Agent；候选为空返回空 Optional。
     *
     * @param role             步骤角色（PLANNER/DEVELOPER/TESTER/REVIEWER 或自定义标签）。
     * @param candidates       已通过团队/角色/ACTIVE/可见性查询过滤的候选池（PRIVATE 均属任务创建人本人）。
     * @param creatorId        任务创建人 ID（仅用于确定性兜底的 PRIVATE 优先级）。
     * @param stepRequirements 步骤声明的能力要求（如 Plan 物化的 requiredCapabilities），作为决策
     *                         Agent 的额外参考上下文，不参与结构化打分；可为 null。
     */
    public Optional<AgentEntity> decide(String role, List<AgentEntity> candidates, UUID creatorId,
                                        List<String> stepRequirements) {
        return decide(role, candidates, creatorId, stepRequirements, null);
    }

    /**
     * 带 Plan 先验的决策重载：{@code suggestedAgentId} 是 Plan Agent 给出的建议 Agent id
     * （联合规划）。建议非空且在候选池内直接采用（省一次 LLM 调用）；不在池内（角色不匹配 /
     * 已下线 / 越权）则记日志忽略，退回常规决策。绝不因建议引入池外 Agent。
     *
     * @param suggestedAgentId Plan 建议的 Agent id；可为 null（无建议时走常规决策）。
     */
    public Optional<AgentEntity> decide(String role, List<AgentEntity> candidates, UUID creatorId,
                                        List<String> stepRequirements, UUID suggestedAgentId) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (suggestedAgentId != null) {
            Optional<AgentEntity> prior = candidates.stream()
                    .filter(candidate -> candidate.getId() != null && candidate.getId().equals(suggestedAgentId))
                    .findFirst();
            if (prior.isPresent()) {
                return prior;
            }
            log.warn("suggested agent not in candidate pool, ignored role={} suggested={}", role, suggestedAgentId);
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        Optional<UUID> chosen = askDecisionAgent(role, candidates, stepRequirements);
        if (chosen.isPresent()) {
            Optional<AgentEntity> match = candidates.stream()
                    .filter(candidate -> candidate.getId() != null && candidate.getId().equals(chosen.get()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
            log.warn("decision agent returned agent outside candidate pool, fallback role={} chosen={}", role,
                    chosen.get());
        }
        return Optional.of(fallback(candidates, creatorId));
    }

    /**
     * 调决策 Agent 选出最合适的候选 id。失败/无法解析返回空，由调用方走确定性兜底。
     */
    private Optional<UUID> askDecisionAgent(String role, List<AgentEntity> candidates,
                                            List<String> stepRequirements) {
        // 注意：complete() 强制 response_format=json_object（DeepSeek/OpenAI 要求 prompt 必须出现
        // "json" 字样，否则 400），因此这里必须输出 JSON 对象并在提示词中显式声明 JSON 输出。
        String system = "你是 Qgents 的 Agent 分配决策器。给定一个步骤角色和一组候选 Agent（各含名称、"
                + "角色与用途描述），请选出最合适完成该步骤角色的一个 Agent。判断依据：候选的 role 是否"
                + "匹配步骤角色，以及 description 描述的职责与步骤角色期望是否一致；若步骤声明了能力要求，"
                + "还需判断候选是否具备相应能力。"
                + "只输出一个 JSON 对象（不要任何解释、标点或代码围栏）："
                + "选中时 {\"agentId\": \"<候选 id 的 UUID 字符串>\"}；"
                + "若所有候选都无法胜任，输出 {\"agentId\": \"NONE\"}。";
        StringBuilder user = new StringBuilder("步骤角色：").append(role);
        if (stepRequirements != null && !stepRequirements.isEmpty()) {
            user.append("\n步骤能力要求：").append(String.join("、", stepRequirements));
        }
        user.append("\n候选 Agent：");
        for (AgentEntity candidate : candidates) {
            user.append("\n- id: ").append(candidate.getId());
            user.append(", name: ").append(nullToBlank(candidate.getName()));
            user.append(", role: ").append(nullToBlank(candidate.getRole()));
            user.append(", description: ").append(nullToBlank(candidate.getDescription()));
        }
        user.append("\n请输出 JSON 对象（{\"agentId\": \"...\"}）。");
        try {
            String raw = llm.complete(system, user.toString());
            return parseAgentId(raw, candidates);
        } catch (RuntimeException e) {
            log.warn("agent decision llm call failed, fallback role={} category={} message={}", role,
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析决策 Agent 输出为候选池内的 Agent id。期望 JSON {@code {"agentId": "..."}}；
     * 兼容裸 UUID / NONE（历史输出）。NONE / 非法 UUID / 池外 id → 空（不采信）。
     */
    private Optional<UUID> parseAgentId(String raw, List<AgentEntity> candidates) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            int first = cleaned.indexOf('\n');
            int last = cleaned.lastIndexOf("```");
            if (first >= 0 && last > first) {
                cleaned = cleaned.substring(first + 1, last).strip();
            }
        }
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        // 优先解析 JSON 对象 {"agentId": "..."}
        if (cleaned.startsWith("{")) {
            try {
                int key = cleaned.indexOf("\"agentId\"");
                if (key >= 0) {
                    int colon = cleaned.indexOf(':', key);
                    int quoteStart = cleaned.indexOf('"', colon + 1);
                    int quoteEnd = quoteStart < 0 ? -1 : cleaned.indexOf('"', quoteStart + 1);
                    if (quoteStart >= 0 && quoteEnd > quoteStart) {
                        cleaned = cleaned.substring(quoteStart + 1, quoteEnd).strip();
                    }
                }
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        if (cleaned.isEmpty() || "NONE".equalsIgnoreCase(cleaned)) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(cleaned);
            boolean inPool = candidates.stream().anyMatch(candidate -> id.equals(candidate.getId()));
            return inPool ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            log.warn("agent decision output not a valid agent id, fallback raw={}", raw);
            return Optional.empty();
        }
    }

    /**
     * 确定性兜底：创建者本人的 PRIVATE Agent 优先，其次名称升序取第一个。
     */
    private AgentEntity fallback(List<AgentEntity> candidates, UUID creatorId) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt((AgentEntity candidate) ->
                                "PRIVATE".equals(candidate.getVisibility()) ? 0 : 1)
                        .thenComparing(AgentEntity::getName, Comparator.nullsLast(String::compareTo)))
                .findFirst()
                .orElse(null);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
