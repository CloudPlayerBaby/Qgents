package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.dto.AgentResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.util.List;
import java.util.UUID;

/** Team-scoped Agent visibility and read operations. */
@Service
public class AgentService {
    private final AgentMapper agentMapper;
    private final TeamMemberMapper teamMemberMapper;

    public AgentService(AgentMapper agentMapper, TeamMemberMapper teamMemberMapper) {
        this.agentMapper = agentMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    public List<AgentResponse> list(UUID actorId, UUID teamId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actorId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getTeamId, teamId)
                        .eq(AgentEntity::getStatus, "ACTIVE")
                        .orderByAsc(AgentEntity::getName))
                .stream()
                .filter(agent -> "TEAM".equals(agent.getVisibility()) || actorId.equals(agent.getCreatedBy()))
                .map(agent -> toResponse(agent, actorId.equals(agent.getCreatedBy())))
                .toList();
    }

    private AgentResponse toResponse(AgentEntity agent, boolean owner) {
        return new AgentResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(), agent.getRole(),
                agent.getCapabilities(), owner ? agent.getPrompt() : null, agent.getVisibility(), agent.getStatus(),
                agent.getCreatedBy() == null ? null : agent.getCreatedBy().toString());
    }
}
