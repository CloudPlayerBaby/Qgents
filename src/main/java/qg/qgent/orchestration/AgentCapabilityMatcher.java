package qg.qgent.orchestration;

import qg.qgent.entity.AgentEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * Agent 能力匹配选择器：在角色候选池中按「能力与步骤角色的匹配度」选出最合适的 Agent。
 * <p>
 * 选择规则（与原有「PRIVATE 无条件优先」相比的改进）：
 * <ol>
 *   <li><b>能力约束过滤</b>：先看候选的 capabilities 是否命中该角色的期望能力标签；存在命中者时，
 *       只在命中者中选（能力完全不匹配的 Agent 不参与，避免「名字排前面但能力不符」被选中）；</li>
 *   <li><b>能力最适合优先</b>：按命中的期望能力数量降序，命中越多越优先；</li>
 *   <li><b>能力相同则个人优先</b>：匹配度相同时，任务创建人自己的 PRIVATE Agent 优先于 TEAM Agent
 *       （个人变体覆盖团队默认）；</li>
 *   <li><b>名称兜底</b>：匹配度与可见性都相同时按名称升序取第一个（确定性）；</li>
 *   <li><b>保底</b>：所有候选都不命中期望能力时退化为全部候选（避免任务因查不到合适 Agent 而挂掉），
 *       仍按 个人优先→名称序 选择。</li>
 * </ol>
 * 纯逻辑、无 I/O、无 LLM，可独立单元测试。
 */
public final class AgentCapabilityMatcher {

    /**
     * 角色 → 期望能力标签（大小写不敏感；与 {@code CapabilityToolRegistry.WRITE_CAPABILITIES} 及
     * AgentPreseedInitializer 的预置标签对齐）。自定义角色无映射 → 匹配度恒 0（不约束，退化规则）。
     */
    private static final Map<String, Set<String>> EXPECTED_CAPABILITIES_BY_ROLE = Map.of(
            "PLANNER", Set.of("planning", "analysis"),
            "DEVELOPER", Set.of("coding", "implementation", "write"),
            "TESTER", Set.of("testing", "verification", "test"),
            "REVIEWER", Set.of("review", "quality"));

    private AgentCapabilityMatcher() {
    }

    /**
     * 计算一个 Agent 的能力与步骤角色的匹配度：capabilities 中命中期望能力标签的数量（大小写不敏感）。
     * 自定义角色 / capabilities 为 null 或空 → 0。
     */
    public static int matchScore(String role, List<String> capabilities) {
        Set<String> expected = EXPECTED_CAPABILITIES_BY_ROLE.get(role);
        if (expected == null || capabilities == null || capabilities.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String capability : capabilities) {
            if (capability != null && expected.contains(capability.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    /**
     * 从候选池中选出最合适的 Agent；候选为空返回 null。
     *
     * @param role       步骤角色（PLANNER/DEVELOPER/TESTER/REVIEWER 或自定义标签）。
     * @param candidates 已通过团队/角色/ACTIVE/可见性查询过滤的候选池（PRIVATE 均属任务创建人本人）。
     * @param creatorId  任务创建人 ID（仅用于 PRIVATE 优先级的语义声明；候选池已保证归属）。
     */
    public static AgentEntity pickBest(String role, List<AgentEntity> candidates, java.util.UUID creatorId) {
        return pickBest(role, List.of(), candidates, creatorId);
    }

    /**
     * 按步骤显式能力要求选择 Agent。显式要求优先于角色通用标签：有要求时候选必须至少命中一个，
     * 再按命中数量、PRIVATE、名称做稳定排序；无显式要求时保持角色能力的既有选择规则。
     */
    public static AgentEntity pickBest(String role, List<String> requiredCapabilities, List<AgentEntity> candidates,
                                       java.util.UUID creatorId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<String> required = requiredCapabilities == null ? List.of() : requiredCapabilities.stream()
                .filter(java.util.Objects::nonNull).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        if (!required.isEmpty()) {
            List<AgentEntity> matching = candidates.stream()
                    .filter(candidate -> explicitMatchScore(required, candidate.getCapabilities()) == required.size()).toList();
            if (matching.isEmpty()) {
                return null;
            }
            return matching.stream().sorted(Comparator
                            .comparingInt((AgentEntity candidate) -> explicitMatchScore(required,
                                    candidate.getCapabilities())).reversed()
                            .thenComparingInt(candidate -> "PRIVATE".equals(candidate.getVisibility()) ? 0 : 1)
                            .thenComparing(AgentEntity::getName, Comparator.nullsLast(String::compareTo)))
                    .findFirst().orElse(null);
        }
        // 能力约束过滤：存在命中期望能力者时只在命中者中选
        List<AgentEntity> scoped = candidates.stream()
                .filter(candidate -> matchScore(role, candidate.getCapabilities()) > 0)
                .toList();
        if (scoped.isEmpty()) {
            scoped = candidates;
        }
        return scoped.stream()
                .sorted(Comparator
                        .comparingInt((AgentEntity candidate) -> matchScore(role, candidate.getCapabilities()))
                        .reversed()
                        .thenComparingInt(candidate -> "PRIVATE".equals(candidate.getVisibility()) ? 0 : 1)
                        .thenComparing(AgentEntity::getName, Comparator.nullsLast(String::compareTo)))
                .findFirst()
                .orElse(null);
    }

    private static int explicitMatchScore(List<String> required, List<String> capabilities) {
        if (capabilities == null) {
            return 0;
        }
        return (int) capabilities.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT)).filter(required::contains).count();
    }
}
