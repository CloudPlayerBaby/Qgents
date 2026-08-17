package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * 编排助手 Agent（ORCHESTRATOR 角色）管理：任务进度卡片与最终 Diff 审核卡片的统一发送身份。
 * <p>
 * 设计约束（题目文档 §二）：回群发 Diff 卡片 / MR 链接 / 任务状态卡片的主语是统一的编排
 * Agent，而不是每个步骤 Agent 各自发言。因此每个团队恒有一条 TEAM 可见、ACTIVE 的
 * ORCHESTRATOR Agent 记录，由建团队事务、启动预置与存量迁移三处幂等保证存在。
 * <p>
 * 该 Agent 不参与 TaskStep 分配（validateAgent 按步骤角色匹配，ORCHESTRATOR 不会被步骤
 * 引用），只作为群聊消息的发送者身份；多 Agent 协作架构（PLANNER/DEVELOPER/TESTER/
 * REVIEWER 分工执行）不受影响。
 */
@Slf4j
@Service
public class OrchestratorAgentService {

    /** 编排助手角色标签；与 task_runs.role 枚举中的 ORCHESTRATOR 一致，但不用于步骤分配。 */
    public static final String ORCHESTRATOR_ROLE = "ORCHESTRATOR";

    /** 展示名（群聊卡片发送者昵称）。 */
    public static final String ORCHESTRATOR_NAME = "编排助手";

    private final AgentMapper agentMapper;
    private final ProjectMapper projectMapper;

    public OrchestratorAgentService(AgentMapper agentMapper, ProjectMapper projectMapper) {
        this.agentMapper = agentMapper;
        this.projectMapper = projectMapper;
    }

    /**
     * 幂等保证团队存在编排助手 Agent：已存在 ACTIVE 记录直接返回，否则创建。
     * agents 表对 (team_id, role) 无唯一约束（团队可拥有多个同角色业务 Agent），
     * 极小概率的并发插入会产生重复记录，消费方按名称序取第一条，展示不受影响。
     *
     * @param teamId      团队 ID
     * @param ownerUserId 创建人（团队 Owner），作为 Agent 的 created_by
     */
    @Transactional
    public AgentEntity ensureForTeam(UUID teamId, UUID ownerUserId) {
        AgentEntity existing = selectActive(teamId);
        if (existing != null) {
            return existing;
        }
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(teamId);
        agent.setCreatedBy(ownerUserId);
        agent.setName(ORCHESTRATOR_NAME);
        agent.setRole(ORCHESTRATOR_ROLE);
        agent.setCapabilities(List.of("orchestration"));
        agent.setPrompt("你是 Qgents 的编排助手，负责把任务执行进度与最终 Diff 审核卡片回群，不执行具体编码工作。");
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 并发兜底：撞唯一键时回查已存在记录
            AgentEntity concurrent = selectActive(teamId);
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
        log.info("预置编排助手 Agent：team={} agentId={}", teamId, agent.getId());
        return agent;
    }

    /**
     * 解析任务所属团队的编排助手 Agent ID，供卡片发送身份使用。
     * 查不到（团队无记录、项目缺失等）返回 null，调用方降级为 SYSTEM 系统消息，
     * 保证任务结果卡片永不因缺少发送者而丢失。
     */
    public UUID resolveIdForTask(TaskEntity task) {
        if (task == null || task.getProjectId() == null) {
            return null;
        }
        try {
            ProjectEntity project = projectMapper.selectById(task.getProjectId());
            if (project == null || project.getTeamId() == null) {
                return null;
            }
            AgentEntity agent = selectActive(project.getTeamId());
            return agent == null ? null : agent.getId();
        } catch (RuntimeException e) {
            log.warn("orchestrator agent resolve failed, taskId={}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    private AgentEntity selectActive(UUID teamId) {
        List<AgentEntity> candidates = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, teamId)
                .eq(AgentEntity::getRole, ORCHESTRATOR_ROLE)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .eq(AgentEntity::getVisibility, "TEAM")
                .orderByAsc(AgentEntity::getName)
                .last("limit 1"));
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
