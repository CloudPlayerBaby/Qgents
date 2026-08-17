package qg.qgent.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.service.DefaultAgentProvisioner;

import java.util.List;

/**
 * 默认 Agent 存量兜底预置。
 * <p>
 * 建团队时已在事务内补齐团队默认 Agent（见 {@link DefaultAgentProvisioner}），本组件只负责
 * 应用启动时对存量 ACTIVE 团队幂等补齐（含部署前已存在的团队），逻辑全部收敛在
 * {@link DefaultAgentProvisioner#ensureForTeam}，不再维护第二份角色清单，避免两处漂移。
 * <p>
 * 关闭开关：{@code app.agent-preseed.enabled=false}（默认 true）。
 */
@Component
@ConditionalOnProperty(name = "app.agent-preseed.enabled", havingValue = "true", matchIfMissing = true)
public class AgentPreseedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentPreseedInitializer.class);

    private final TeamMapper teamMapper;
    private final DefaultAgentProvisioner defaultAgents;

    public AgentPreseedInitializer(TeamMapper teamMapper, DefaultAgentProvisioner defaultAgents) {
        this.teamMapper = teamMapper;
        this.defaultAgents = defaultAgents;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<TeamEntity> activeTeams = teamMapper.selectList(
                Wrappers.<TeamEntity>lambdaQuery().eq(TeamEntity::getStatus, "ACTIVE"));
        for (TeamEntity team : activeTeams) {
            defaultAgents.ensureForTeam(team.getId(), team.getOwnerUserId());
        }
        log.info("默认 Agent 存量兜底预置完成：teams={}", activeTeams.size());
    }
}