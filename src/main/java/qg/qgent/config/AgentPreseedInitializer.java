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
import qg.qgent.service.OrchestratorAgentService;

import java.util.List;
import java.util.Map;

/**
 * 默认 Agent 预置（解决 agents 表无预置数据、sendAsAgent 方案 A 查不到匹配 Agent 的问题）。
 * <p>
 * 应用启动时，对每个 ACTIVE 团队幂等地补齐 DEVELOPER / TESTER / REVIEWER 三个
 * TEAM 可见、ACTIVE 的默认 Agent；团队已存在该角色 Agent 时跳过，不会重复创建、不改已有数据。
 * 同时为每个团队幂等补齐 ORCHESTRATOR 编排助手（任务卡片统一发送者），逻辑收敛在
 * {@link OrchestratorAgentService}；新建团队在建团队事务内即时预置，此处兜底存量团队。
 * <p>
 * 关闭开关：{@code app.agent-preseed.enabled=false}（默认 true）。
 */
@Component
@ConditionalOnProperty(name = "app.agent-preseed.enabled", havingValue = "true", matchIfMissing = true)
public class AgentPreseedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentPreseedInitializer.class);

    /**
     * 角色 → 默认展示名。
     */
    private static final Map<String, String> DEFAULT_NAMES = Map.of(
            "DEVELOPER", "开发 Agent",
            "TESTER", "测试 Agent",
            "REVIEWER", "审查 Agent");

    /**
     * 角色 → 默认用途描述（该 Agent 干什么用）。
     */
    private static final Map<String, String> DEFAULT_DESCRIPTIONS = Map.of(
            "DEVELOPER", "负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检",
            "TESTER", "负责运行测试并依据真实执行结果判定是否满足验收",
            "REVIEWER", "负责审查本次改动是否符合需求、质量与安全要求");

    private final TeamMapper teamMapper;
    private final AgentMapper agentMapper;
    private final OrchestratorAgentService orchestratorAgents;

    public AgentPreseedInitializer(TeamMapper teamMapper, AgentMapper agentMapper,
                                   OrchestratorAgentService orchestratorAgents) {
        this.teamMapper = teamMapper;
        this.agentMapper = agentMapper;
        this.orchestratorAgents = orchestratorAgents;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<TeamEntity> activeTeams = teamMapper.selectList(
                Wrappers.<TeamEntity>lambdaQuery().eq(TeamEntity::getStatus, "ACTIVE"));
        for (TeamEntity team : activeTeams) {
            for (String role : DEFAULT_NAMES.keySet()) {
                ensureRoleAgent(team, role);
            }
            // 编排助手（ORCHESTRATOR）单独走 OrchestratorAgentService，保持身份与提示词单一来源
            orchestratorAgents.ensureForTeam(team.getId(), team.getOwnerUserId());
        }
    }

    /**
     * 团队内已存在该角色 ACTIVE Agent 则跳过，否则创建一个 TEAM 可见的默认 Agent。
     */
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
        agent.setDescription(DEFAULT_DESCRIPTIONS.get(role));
        agent.setPrompt("你是 Qgents 的" + DEFAULT_NAMES.get(role) + "，请按任务要求完成" + role + "阶段的工作。");
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        agentMapper.insert(agent);
        log.info("预置默认 Agent：team={} role={} agentId={}", team.getId(), role, agent.getId());
    }
}
