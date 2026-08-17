package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.TeamMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队默认 Agent 预置（团队级共享资源）。
 * <p>
 * 需求语义：默认 Agent 属于**团队**，在**建团队事务内**一次补齐；团队成员共享同一组默认
 * Agent，而不是每人一套。默认集合为 4 个工作角色（PLANNER/DEVELOPER/TESTER/REVIEWER）
 * + 1 个编排助手（ORCHESTRATOR，卡片统一发送身份，不参与步骤分配）。
 * <p>
 * 并发安全：agents 表有唯一索引 {@code uk_agents_team_default_role}（生成列 default_role
 * 仅对默认 Agent 暴露 role），保证「每团队每角色至多一条默认 Agent」；并发重复插入被
 * 唯一约束挡住后捕获 {@link DuplicateKeyException} 回查已存在记录返回，不产生重复定义。
 * 多条任务可同时挂同一个默认 Agent 定义运行（每次编排按 id 现场 new 一个执行实例），
 * 本约束只防止定义重复，不限制任务并发。
 * <p>
 * 调用点：{@link TeamService#create}（建团队事务内）、{@code AgentPreseedInitializer}
 * （应用启动存量兜底）、{@link TaskService#create}（任务发起惰性兜底）。
 */
@Slf4j
@Service
public class DefaultAgentProvisioner {

    /** 工作角色默认规格：name / description / prompt 单一来源。 */
    private static final Map<String, DefaultSpec> WORKER_ROLES = new LinkedHashMap<>();

    static {
        WORKER_ROLES.put("PLANNER", new DefaultSpec("规划 Agent",
                "负责分析需求并制定实现计划，输出可执行、可冻结的实现步骤",
                "你是 Qgents 的规划 Agent，请分析需求并制定可执行、可冻结的实现计划。"));
        WORKER_ROLES.put("DEVELOPER", new DefaultSpec("开发 Agent",
                "负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检",
                "你是 Qgents 的开发 Agent，请按任务要求完成开发阶段的工作。"));
        WORKER_ROLES.put("TESTER", new DefaultSpec("测试 Agent",
                "负责运行测试并依据真实执行结果判定是否满足验收",
                "你是 Qgents 的测试 Agent，请按任务要求完成测试阶段的工作。"));
        WORKER_ROLES.put("REVIEWER", new DefaultSpec("审查 Agent",
                "负责审查本次改动是否符合需求、质量与安全要求",
                "你是 Qgents 的审查 Agent，请按任务要求完成审查阶段的工作。"));
    }

    private final TeamMapper teamMapper;
    private final AgentMapper agentMapper;
    private final OrchestratorAgentService orchestratorAgents;

    public DefaultAgentProvisioner(TeamMapper teamMapper, AgentMapper agentMapper,
                                   OrchestratorAgentService orchestratorAgents) {
        this.teamMapper = teamMapper;
        this.agentMapper = agentMapper;
        this.orchestratorAgents = orchestratorAgents;
    }

    /**
     * 为团队幂等补齐全部默认 Agent（4 工作角色 + ORCHESTRATOR）。
     *
     * @param teamId      团队 ID
     * @param ownerUserId 团队 Owner（作为默认 Agent 的 created_by；团队创建/预置路径已知）
     */
    @Transactional
    public void ensureForTeam(UUID teamId, UUID ownerUserId) {
        for (Map.Entry<String, DefaultSpec> entry : WORKER_ROLES.entrySet()) {
            ensureRole(teamId, ownerUserId, entry.getKey(), entry.getValue());
        }
        // 编排助手单独走 OrchestratorAgentService，身份与提示词保持单一来源
        orchestratorAgents.ensureForTeam(teamId, ownerUserId);
    }

    /**
     * 团队 ID 重载：内部查团队取 Owner 后补齐默认 Agent。供任务发起等不持有 Owner 的场景使用。
     * 团队不存在时静默跳过（不阻断任务）。
     */
    @Transactional
    public void ensureForTeam(UUID teamId) {
        if (teamId == null) {
            return;
        }
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            return;
        }
        ensureForTeam(teamId, team.getOwnerUserId());
    }

    /**
     * 单角色幂等创建：先按「is_default 标记或已知默认名」判断是否已存在，存在则跳过；
     * 否则插入（带 is_default=true）。并发撞唯一索引时捕获
     * {@link DuplicateKeyException} 回查已存在记录，不产生重复定义。
     */
    private void ensureRole(UUID teamId, UUID ownerUserId, String role, DefaultSpec spec) {
        if (selectDefault(teamId, role, spec) != null) {
            return;
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(teamId);
        agent.setCreatedBy(ownerUserId);
        agent.setName(spec.name());
        agent.setRole(role);
        agent.setDescription(spec.description());
        agent.setPrompt(spec.prompt());
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        agent.setIsDefault(true);
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一索引 uk_agents_team_default_role 挡住同角色默认 Agent 重复插入
            AgentEntity concurrent = selectDefault(teamId, role, spec);
            if (concurrent == null) {
                throw e;
            }
        }
        log.info("预置默认 Agent：team={} role={} agentId={}", teamId, role, agent.getId());
    }

    /**
     * 命中条件：同团队同角色 ACTIVE，且为「is_default 标记的默认 Agent」或「已知默认名」。
     * 兼容迁移前未打标的历史默认 Agent（按默认名识别），避免迁移窗口内重复创建。
     */
    private AgentEntity selectDefault(UUID teamId, String role, DefaultSpec spec) {
        List<AgentEntity> candidates = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, teamId)
                .eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.eq(AgentEntity::getIsDefault, true)
                        .or().eq(AgentEntity::getName, spec.name()))
                .last("limit 1"));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 默认 Agent 规格值对象（name/description/prompt）。 */
    private record DefaultSpec(String name, String description, String prompt) {
    }
}