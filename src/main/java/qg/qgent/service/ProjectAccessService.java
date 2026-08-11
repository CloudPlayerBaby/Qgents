package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.util.UUID;

/**
 * 项目级授权校验服务。
 * 授权必须由服务端依据已认证身份、项目成员关系和资源归属判断，
 * 不信任客户端提交的 userId、role 等字段。
 */
@Service
public class ProjectAccessService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;

    public ProjectAccessService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
            TeamMemberMapper teamMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    /**
     * 要求调用者是该项目的成员。
     *
     * @param projectId 项目ID
     * @param userId    已认证用户ID
     * @return 调用者在项目中的角色（PROJECT_ADMIN / PROJECT_MEMBER）
     * @throws qg.qgent.api.ApiException 项目不存在或调用者不是成员时返回 404 PROJECT_NOT_FOUND
     */
    public String requireProjectMember(UUID projectId, UUID userId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !"ACTIVE".equals(project.getStatus())) {
            throw notFound();
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(projectId, userId);
        if (member == null) {
            // 资源不可见按 404 处理，避免泄露项目存在性
            throw notFound();
        }
        return member.getRole();
    }

    /**
     * 要求调用者是项目 Admin（含 Team Owner 对本团队项目的兜底管理权限）。
     *
     * @throws qg.qgent.api.ApiException 成员但非 Admin 时返回 403 PROJECT_ADMIN_REQUIRED
     */
    public void requireProjectAdmin(UUID projectId, UUID userId) {
        String role = requireProjectMember(projectId, userId);
        if ("PROJECT_ADMIN".equals(role) || isTeamOwner(projectId, userId)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
    }

    /**
     * 判断调用者是资源创建者或项目 Admin（含 Team Owner 兜底）。
     *
     * @param creatorId 资源创建者ID，可能为空
     */
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

    private boolean isTeamOwner(UUID projectId, UUID userId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        TeamMemberEntity member = teamMemberMapper.selectByTeamAndUser(project.getTeamId(), userId);
        return member != null && "TEAM_OWNER".equals(member.getRole());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
    }
}
