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
 *   <li>候选为空 → 空（不硬选）；候选唯一 → 直接返回（省一次 LLM 调用）；</li>
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
     * @param role       步骤角色（PLANNER/DEVELOPER/TESTER/REVIEWER 或自定义标签）。
     * @param candidates 已通过团队/角色/ACTIVE/可见性查询过滤的候选池（PRIVATE 均属任务创建人本人）。
     * @param creatorId  任务创建人 ID（仅用于确定性兜底的 PRIVATE 优先级）。
     */
    public Optional<AgentEntity> decide(String role, List<AgentEntity> candidates, UUID creatorId) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        Optional<UUID> chosen = askDecisionAgent(role, candidates);
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
    private Optional<UUID> askDecisionAgent(String role, List<AgentEntity> candidates) {
        String system = "你是 Qgents 的 Agent 分配决策器。给定一个步骤角色和一组候选 Agent（各含名称、"
                + "角色与用途描述），请选出最合适完成该步骤角色的一个 Agent。判断依据：候选的 role 是否"
                + "匹配步骤角色，以及 description 描述的职责与步骤角色期望是否一致。只输出选中 Agent 的 id"
                + "（UUID 字符串），不要任何解释、标点或代码围栏；若所有候选都无法胜任，只输出 NONE。";
        StringBuilder user = new StringBuilder("步骤角色：").append(role);
        user.append("\n候选 Agent：");
        for (AgentEntity candidate : candidates) {
            user.append("\n- id: ").append(candidate.getId());
            user.append(", name: ").append(nullToBlank(candidate.getName()));
            user.append(", role: ").append(nullToBlank(candidate.getRole()));
            user.append(", description: ").append(nullToBlank(candidate.getDescription()));
        }
        user.append("\n请输出最合适的 Agent id。");
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
     * 解析决策 Agent 输出为候选池内的 Agent id。NONE / 非法 UUID / 池外 id → 空（不采信）。
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
