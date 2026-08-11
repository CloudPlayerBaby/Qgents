package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.util.UUID;

/** 服务端项目访问边界，不信任客户端提交的角色或用户标识。 */
@Service
public class ProjectAccessService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public ProjectAccessService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
            TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    public String requireProjectMember(UUID projectId, UUID userId) {
        ProjectEntity project = requireProject(projectId, false);
        return requireAccess(project, userId);
    }

    public void requireProjectAdmin(UUID projectId, UUID userId) {
        requireAdmin(requireProject(projectId, false), userId);
    }

    /** 归档项目必须仍可授权恢复，因此该入口允许读取 ARCHIVED 项目。 */
    public void requireProjectAdminAnyState(UUID projectId, UUID userId) {
        requireAdmin(requireProject(projectId, true), userId);
    }

    /** 对已加锁项目执行不限状态的 Admin 校验，供状态错误前的防泄漏授权使用。 */
    public void requireProjectAdminAnyState(ProjectEntity project, UUID userId) {
        requireAdmin(project, userId);
    }

    public String requireAccess(ProjectEntity project, UUID userId) {
        // canonical Team Owner 是跨项目兜底管理员，不要求存在 project_members 行。
        if (isCanonicalTeamOwner(project.getTeamId(), userId)) {
            return "PROJECT_ADMIN";
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member == null) {
            throw notFound();
        }
        return member.getRole();
    }

    public void requireAdmin(ProjectEntity project, UUID userId) {
        if (isCanonicalTeamOwner(project.getTeamId(), userId)) {
            return;
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        if (member != null && "PROJECT_ADMIN".equals(member.getRole())) {
            return;
        }
        if (member == null) {
            throw notFound();
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
    }

    /** 同时核对 teams.owner_user_id 与成员角色，额外 TEAM_OWNER 不能获得兜底权限。 */
    public boolean isCanonicalTeamOwner(UUID teamId, UUID userId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null || !userId.equals(team.getOwnerUserId())) {
            return false;
        }
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(teamId, userId);
        return member != null && "TEAM_OWNER".equals(member.getRole());
    }

    public boolean isOwnerOrAdmin(UUID creatorId, UUID projectId, UUID userId) {
        if (creatorId != null && creatorId.equals(userId)) {
            return true;
        }
        try {
            requireProjectAdmin(projectId, userId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private ProjectEntity requireProject(UUID projectId, boolean allowArchived) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || (!allowArchived && !"ACTIVE".equals(project.getStatus()))) {
            throw notFound();
        }
        return project;
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
    }
}
