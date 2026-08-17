package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.AgentEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 能力匹配选择器测试：能力约束过滤、能力最适合优先、能力相同则个人优先、名称兜底、全不匹配保底。
 */
class AgentCapabilityMatcherTest {

    private final UUID creatorId = UUID.randomUUID();

    private AgentEntity agent(UUID id, String name, String visibility, List<String> capabilities) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setVisibility(visibility);
        agent.setCapabilities(capabilities);
        return agent;
    }

    private AgentEntity team(UUID id, String name, List<String> capabilities) {
        return agent(id, name, "TEAM", capabilities);
    }

    private AgentEntity privateAgent(UUID id, String name, List<String> capabilities) {
        return agent(id, name, "PRIVATE", capabilities);
    }

    @Test
    void matchScoreCountsExpectedCapabilitiesCaseInsensitive() {
        assertThat(AgentCapabilityMatcher.matchScore("DEVELOPER", List.of("coding", "implementation"))).isEqualTo(2);
        assertThat(AgentCapabilityMatcher.matchScore("DEVELOPER", List.of("CODING"))).isEqualTo(1);
        assertThat(AgentCapabilityMatcher.matchScore("DEVELOPER", List.of("java", "spring"))).isEqualTo(0);
        assertThat(AgentCapabilityMatcher.matchScore("DEVELOPER", List.of())).isEqualTo(0);
        assertThat(AgentCapabilityMatcher.matchScore("DEVELOPER", null)).isEqualTo(0);
        // 自定义角色无期望能力映射 → 0
        assertThat(AgentCapabilityMatcher.matchScore("CUSTOM", List.of("coding"))).isEqualTo(0);
    }

    @Test
    void picksAgentWithHighestCapabilityMatch() {
        AgentEntity coding = team(UUID.randomUUID(), "通用开发", List.of("coding", "implementation"));
        AgentEntity javaOnly = team(UUID.randomUUID(), "Java专家", List.of("java"));
        AgentEntity fullStack = team(UUID.randomUUID(), "全栈", List.of("coding", "implementation", "write"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(javaOnly, coding, fullStack), creatorId);

        assertThat(best).isSameAs(fullStack);
    }

    @Test
    void filtersOutAgentsWithNoMatchingCapability() {
        AgentEntity coding = team(UUID.randomUUID(), "通用开发", List.of("coding", "implementation"));
        AgentEntity unrelated = team(UUID.randomUUID(), "名字靠前但能力不符", List.of("java", "spring"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(unrelated, coding), creatorId);

        // 名字靠前的无关 Agent 被能力过滤掉，选能力匹配的
        assertThat(best).isSameAs(coding);
    }

    @Test
    void prefersPrivateAgentWhenCapabilityMatchIsEqual() {
        AgentEntity teamAgent = team(UUID.randomUUID(), "团队开发", List.of("coding", "implementation"));
        AgentEntity personal = privateAgent(UUID.randomUUID(), "个人开发", List.of("coding", "implementation"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(teamAgent, personal), creatorId);

        // 能力描述一模一样 → 调用个人的
        assertThat(best).isSameAs(personal);
    }

    @Test
    void capabilityMatchOutweighsPrivatePreference() {
        // 个人 Agent 能力完全不匹配，团队 Agent 能力匹配 → 选团队（能力优先于个人）
        AgentEntity personalUnrelated = privateAgent(UUID.randomUUID(), "个人Java", List.of("java"));
        AgentEntity teamMatching = team(UUID.randomUUID(), "团队开发", List.of("coding", "implementation"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(personalUnrelated, teamMatching),
                creatorId);

        assertThat(best).isSameAs(teamMatching);
    }

    @Test
    void fallsBackToAllCandidatesWhenNoneMatch() {
        // 全部候选能力都不匹配 → 保底全部候选，仍按 个人优先→名称序
        AgentEntity unrelatedTeam = team(UUID.randomUUID(), "B团队", List.of("java"));
        AgentEntity unrelatedPersonal = privateAgent(UUID.randomUUID(), "A个人", List.of("python"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(unrelatedTeam, unrelatedPersonal),
                creatorId);

        assertThat(best).isSameAs(unrelatedPersonal);
    }

    @Test
    void picksByNameWhenScoreAndVisibilityEqual() {
        AgentEntity a = team(UUID.randomUUID(), "A", List.of("coding"));
        AgentEntity b = team(UUID.randomUUID(), "B", List.of("coding"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(b, a), creatorId);

        assertThat(best).isSameAs(a);
    }

    @Test
    void nullOrEmptyCandidatesReturnNull() {
        assertThat(AgentCapabilityMatcher.pickBest("DEVELOPER", null, creatorId)).isNull();
        assertThat(AgentCapabilityMatcher.pickBest("DEVELOPER", List.of(), creatorId)).isNull();
    }

    @Test
    void customRoleFallsBackToPrivatePreference() {
        // 自定义角色无期望能力映射 → 匹配度全 0 → 保底路径，个人优先
        AgentEntity teamAgent = team(UUID.randomUUID(), "团队", List.of("anything"));
        AgentEntity personal = privateAgent(UUID.randomUUID(), "个人", List.of("anything"));

        AgentEntity best = AgentCapabilityMatcher.pickBest("CUSTOM_ROLE", List.of(teamAgent, personal), creatorId);

        assertThat(best).isSameAs(personal);
    }
}
