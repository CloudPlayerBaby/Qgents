package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.TeamMapper;

import java.util.UUID;

/**
 * 项目维度权限校验（契约 §3.1）。
 * <p>
 * 授权依据只有服务端数据：当前登录用户 ID（来自 Token）与 {@code project_members}/{@code teams} 的归属关系。
 * 客户端传入的 userId/role 一律不作为授权依据。项目不存在或无权访问统一返回 404，不泄露项目是否存在。
 * <p>
 * 项目/团队表属于项目域（后端2），本组件只做只读校验，不负责写入。
 */
@Service
public class ProjectAccessService {
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMapper teamMapper;

    public ProjectAccessService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
            TeamMapper teamMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMapper = teamMapper;
    }

    /**
     * 要求当前用户是项目成员；项目不存在或不可见时返回 404。
     *
     * @param projectId 项目 ID
     * @param userId    当前登录用户 ID
     */
    public void requireMember(UUID projectId, UUID userId) {
        memberOrNotFound(projectId, userId);
    }

    /**
     * 要求当前用户是项目 Admin 或团队 Owner（Team Owner 对团队内项目有兜底管理权限）。
     *
     * @param projectId 项目 ID
     * @param userId    当前登录用户 ID
     */
    public void requireAdmin(UUID projectId, UUID userId) {
        String role = memberOrNotFound(projectId, userId);
        if (!"PROJECT_ADMIN".equals(role) && !teamOwner(projectId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限");
        }
    }

    private String memberOrNotFound(UUID projectId, UUID userId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !"ACTIVE".equals(project.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或无权访问");
        }
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(projectId, userId);
        if (member == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或无权访问");
        }
        return member.getRole();
    }

    private boolean teamOwner(UUID projectId, UUID userId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        TeamEntity team = teamMapper.selectById(project.getTeamId());
        return team != null && userId.equals(team.getOwnerUserId());
    }
}
