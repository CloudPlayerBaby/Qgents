package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 调度 Agent（Agent Dispatcher）：后端统一的「按 step 挑选 Agent」入口。
 * <p>
 * 职责：给定任务与步骤角色，从团队候选池中挑选最合适的执行 Agent。候选池查询
 * （团队 + 角色 + ACTIVE + 可见性）与决策（{@link AgentMatchDecider}，LLM 决策 +
 * 确定性兜底）全部收敛在本组件，是唯一的选人入口——Plan 物化冻结步骤时经它落
 * {@code assignedAgentId}，并接受 Plan 的建议 Agent（{@code suggestedAgentId}）作为
 * 选人先验。
 * <p>
 * 定位是系统级调度角色：只做「挑人」，自身不落 agents 表、不参与候选池，避免
 * 「谁来选选择器」的自引用；候选池为空或决策失败时返回空，由调用方降级为执行期
 * 内置 Agent 兜底，不使任务失败。{@link #listTeamCandidates} 供 Plan 规划期做只读
 * 候选池快照（联合规划：让 Plan 拆步骤时考虑可用 Agent），不参与选人决策。
 */
@Service
public class AgentDispatcher {

    private final ProjectMapper projects;
    private final AgentMapper agents;
    private final AgentMatchDecider decider;

    public AgentDispatcher(ProjectMapper projects, AgentMapper agents, AgentMatchDecider decider) {
        this.projects = projects;
        this.agents = agents;
        this.decider = decider;
    }

    /**
     * 为任务步骤挑选 Agent：按任务所属团队 + 步骤角色查候选池，交决策器选出最合适者。
     * 项目缺失 / 无团队 / 无候选 / 决策失败均返回空 Optional，不抛异常。
     *
     * @param task               待编排的任务（取项目与创建者）
     * @param role               步骤角色（PLANNER/DEVELOPER/TESTER/REVIEWER 或自定义标签）
     * @param requiredCapabilities 步骤声明的能力要求（决策 Agent 的参考上下文，不参与结构化打分）
     */
    public Optional<AgentEntity> dispatch(TaskEntity task, String role, List<String> requiredCapabilities) {
        return dispatch(task, role, requiredCapabilities, null);
    }

    /**
     * 带 Plan 先验的选人重载：{@code suggestedAgentId} 为 Plan 建议的 Agent id（联合规划）。
     * 建议非空且经 {@link AgentMatchDecider} 池内校验后采用；池外/非法建议被忽略、退回常规决策。
     */
    public Optional<AgentEntity> dispatch(TaskEntity task, String role, List<String> requiredCapabilities,
                                          UUID suggestedAgentId) {
        if (task == null || task.getProjectId() == null || task.getCreatedBy() == null) {
            return Optional.empty();
        }
        ProjectEntity project = projects.selectById(task.getProjectId());
        if (project == null || project.getTeamId() == null) {
            return Optional.empty();
        }
        List<AgentEntity> candidates = queryCandidates(project.getTeamId(), task.getCreatedBy(), role);
        return decider.decide(role, candidates, task.getCreatedBy(), requiredCapabilities, suggestedAgentId);
    }

    /**
     * 规划期只读候选池快照：团队下全部 ACTIVE + 对创建者可见（TEAM，或 PRIVATE 且属其本人）
     * 的 Agent，不限角色、按名称升序。供 Plan Agent 拆步骤时考虑可用 Agent（联合规划），
     * 不参与选人决策；项目缺失 / 无团队 / 参数缺失返回空列表。
     */
    public List<AgentEntity> listTeamCandidates(UUID projectId, UUID creatorId) {
        if (projectId == null || creatorId == null) {
            return List.of();
        }
        ProjectEntity project = projects.selectById(projectId);
        if (project == null || project.getTeamId() == null) {
            return List.of();
        }
        return queryCandidates(project.getTeamId(), creatorId, null);
    }

    /**
     * 团队候选池查询：团队 + ACTIVE + 可见性（TEAM 或 PRIVATE 且属创建人本人）；
     * {@code role} 非空时按角色过滤（选人用），为 null 时返回全角色池（规划快照用）。
     */
    private List<AgentEntity> queryCandidates(UUID teamId, UUID creatorId, String role) {
        var query = Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, teamId)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .and(visibility -> visibility.eq(AgentEntity::getVisibility, "TEAM")
                        .or(owner -> owner.eq(AgentEntity::getVisibility, "PRIVATE")
                                .eq(AgentEntity::getCreatedBy, creatorId)));
        if (role != null) {
            query.eq(AgentEntity::getRole, role);
        }
        return agents.selectList(query.orderByAsc(AgentEntity::getName));
    }
}