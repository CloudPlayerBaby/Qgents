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

/**
 * 调度 Agent（Agent Dispatcher）：后端统一的「按 step 挑选 Agent」入口。
 * <p>
 * 职责：给定任务与步骤角色，从团队候选池中挑选最合适的执行 Agent。候选池查询
 * （团队 + 角色 + ACTIVE + 可见性）与决策（{@link AgentMatchDecider}，LLM 决策 +
 * 确定性兜底）全部收敛在本组件，是唯一的选人入口——Plan 物化冻结步骤时经它落
 * {@code assignedAgentId}。
 * <p>
 * 定位是系统级调度角色：只做「挑人」，自身不落 agents 表、不参与候选池，避免
 * 「谁来选选择器」的自引用；候选池为空或决策失败时返回空，由调用方降级为执行期
 * 内置 Agent 兜底，不使任务失败。
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
        if (task == null || task.getProjectId() == null || task.getCreatedBy() == null) {
            return Optional.empty();
        }
        ProjectEntity project = projects.selectById(task.getProjectId());
        if (project == null || project.getTeamId() == null) {
            return Optional.empty();
        }
        List<AgentEntity> candidates = agents.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, project.getTeamId())
                .eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .and(visibility -> visibility.eq(AgentEntity::getVisibility, "TEAM")
                        .or(owner -> owner.eq(AgentEntity::getVisibility, "PRIVATE")
                                .eq(AgentEntity::getCreatedBy, task.getCreatedBy()))));
        return decider.decide(role, candidates, task.getCreatedBy(), requiredCapabilities);
    }
}