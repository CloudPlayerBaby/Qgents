package qg.qgent.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.TeamMapper;

import java.util.List;
import java.util.Map;

/**
 * 默认 Agent 预置（解决 agents 表无预置数据、sendAsAgent 方案 A 查不到匹配 Agent 的问题）。
 * <p>
 * 应用启动时，对每个 ACTIVE 团队幂等地补齐 DEVELOPER / TESTER / REVIEWER 三个
 * TEAM 可见、ACTIVE 的默认 Agent；团队已存在该角色 Agent 时跳过，不会重复创建、不改已有数据。
 * <p>
 * 关闭开关：{@code app.agent-preseed.enabled=false}（默认 true）。
 */
@Component
@ConditionalOnProperty(name = "app.agent-preseed.enabled", havingValue = "true", matchIfMissing = true)
public class AgentPreseedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentPreseedInitializer.class);

    /** 角色 → 默认展示名。 */
    private static final Map<String, String> DEFAULT_NAMES = Map.of(
            "DEVELOPER", "开发 Agent",
            "TESTER", "测试 Agent",
            "REVIEWER", "审查 Agent");

    /** 角色 → 默认能力标签。 */
    private static final Map<String, List<String>> DEFAULT_CAPABILITIES = Map.of(
            "DEVELOPER", List.of("coding", "implementation"),
            "TESTER", List.of("testing", "verification"),
            "REVIEWER", List.of("review", "quality"));

    private final TeamMapper teamMapper;
    private final AgentMapper agentMapper;

    public AgentPreseedInitializer(TeamMapper teamMapper, AgentMapper agentMapper) {
        this.teamMapper = teamMapper;
        this.agentMapper = agentMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<TeamEntity> activeTeams = teamMapper.selectList(
                Wrappers.<TeamEntity>lambdaQuery().eq(TeamEntity::getStatus, "ACTIVE"));
        for (TeamEntity team : activeTeams) {
            for (String role : DEFAULT_NAMES.keySet()) {
                ensureRoleAgent(team, role);
            }
        }
    }

    /** 团队内已存在该角色 ACTIVE Agent 则跳过，否则创建一个 TEAM 可见的默认 Agent。 */
    private void ensureRoleAgent(TeamEntity team, String role) {
        boolean exists = agentMapper.selectCount(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, team.getId())
                .eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")) > 0;
        if (exists) {
            return;
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(team.getId());
        agent.setCreatedBy(team.getOwnerUserId());
        agent.setName(DEFAULT_NAMES.get(role));
        agent.setRole(role);
        agent.setCapabilities(DEFAULT_CAPABILITIES.getOrDefault(role, List.of()));
        agent.setPrompt("你是 Qgents 的" + DEFAULT_NAMES.get(role) + "，请按任务要求完成" + role + "阶段的工作。");
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        agentMapper.insert(agent);
        log.info("预置默认 Agent：team={} role={} agentId={}", team.getId(), role, agent.getId());
    }
}
