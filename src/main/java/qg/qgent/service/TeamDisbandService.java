package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.UUID;

/**
 * 团队解散时的级联删除执行体。所有删除在单事务内完成，按子表先于父表的
 * 依赖顺序手工清理（相关外键在库中大多未声明 ON DELETE CASCADE）。
 * <p>
 * 只负责数据清理，权限校验（Team Owner）与状态检查由 {@link TeamService#disband} 完成。
 */
@Service
public class TeamDisbandService {
    private final ProjectMapper projectMapper;
    private final NotificationMapper notificationMapper;
    private final EventMapper eventMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final MessageMapper messageMapper;
    private final DiffMapper diffMapper;
    private final DiffReviewBatchMapper diffReviewBatchMapper;
    private final MemoryMapper memoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final MergeRequestMapper mergeRequestMapper;
    private final TaskRunMapper taskRunMapper;
    private final TestRunMapper testRunMapper;
    private final DryRunMapper dryRunMapper;
    private final TaskMapper taskMapper;
    private final WorkspaceMapper workspaceMapper;
    private final TestsetMapper testsetMapper;
    private final SkillMapper skillMapper;
    private final AttachmentMapper attachmentMapper;
    private final GitHubInstallationMapper githubInstallationMapper;
    private final AgentMapper agentMapper;
    private final TeamMapper teamMapper;

    public TeamDisbandService(ProjectMapper projectMapper, NotificationMapper notificationMapper,
                              EventMapper eventMapper, RequirementGroupMapper requirementGroupMapper, MessageMapper messageMapper,
                              DiffMapper diffMapper, DiffReviewBatchMapper diffReviewBatchMapper, MemoryMapper memoryMapper,
                              ProjectRepositoryMapper projectRepositoryMapper, MergeRequestMapper mergeRequestMapper,
                              TaskRunMapper taskRunMapper, TestRunMapper testRunMapper, DryRunMapper dryRunMapper, TaskMapper taskMapper,
                              WorkspaceMapper workspaceMapper, TestsetMapper testsetMapper, SkillMapper skillMapper,
                              AttachmentMapper attachmentMapper, GitHubInstallationMapper githubInstallationMapper,
                              AgentMapper agentMapper, TeamMapper teamMapper) {
        this.projectMapper = projectMapper;
        this.notificationMapper = notificationMapper;
        this.eventMapper = eventMapper;
        this.requirementGroupMapper = requirementGroupMapper;
        this.messageMapper = messageMapper;
        this.diffMapper = diffMapper;
        this.diffReviewBatchMapper = diffReviewBatchMapper;
        this.memoryMapper = memoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.mergeRequestMapper = mergeRequestMapper;
        this.taskRunMapper = taskRunMapper;
        this.testRunMapper = testRunMapper;
        this.dryRunMapper = dryRunMapper;
        this.taskMapper = taskMapper;
        this.workspaceMapper = workspaceMapper;
        this.testsetMapper = testsetMapper;
        this.skillMapper = skillMapper;
        this.attachmentMapper = attachmentMapper;
        this.githubInstallationMapper = githubInstallationMapper;
        this.agentMapper = agentMapper;
        this.teamMapper = teamMapper;
    }

    /**
     * 删除团队及其全部归属数据：先逐项目清理项目下所有子表，再删 GitHub 安装、
     * Agent，最后删团队本体（team_members/team_invitations 由外键 ON DELETE CASCADE 移除）。
     * 本方法在调用方事务内执行（REQUIRED 传播），任一步失败整体回滚。
     *
     * @param teamId 待解散的团队 ID
     */
    @Transactional
    public void deleteTeam(UUID teamId) {
        List<UUID> projectIds = projectMapper.selectList(Wrappers.<ProjectEntity>lambdaQuery()
                .eq(ProjectEntity::getTeamId, teamId)).stream().map(ProjectEntity::getId).toList();
        for (UUID projectId : projectIds) {
            deleteProject(projectId);
        }
        // github_repositories 由 github_installations 外键级联删除
        githubInstallationMapper.delete(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getTeamId, teamId));
        // agent_skill_bindings 由 agents 外键级联删除；须在 messages/task_runs 等引用行清理之后
        agentMapper.delete(Wrappers.<AgentEntity>lambdaQuery().eq(AgentEntity::getTeamId, teamId));
        teamMapper.deleteById(teamId);
    }

    /**
     * 删除单个项目及其全部归属数据。子表先于父表，引用关系严格按外键约束排序；
     * 依赖库内级联的（project_members、project_repositories、task_steps、workspace_repositories、
     * requirement_group_repositories、group_agents、diff_files/diff_comments、memory_message_sources、
     * merge_request 子表、task_run 子表等）不在此重复删除。
     */
    private void deleteProject(UUID projectId) {
        // 引用 requirement_groups 且无级联的行须在 requirement_groups 删除前清理
        notificationMapper.delete(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(NotificationEntity::getProjectId, projectId));
        eventMapper.delete(Wrappers.<EventEntity>lambdaQuery().eq(EventEntity::getProjectId, projectId));

        List<UUID> groupIds = requirementGroupMapper.selectList(Wrappers.<RequirementGroupEntity>lambdaQuery()
                        .eq(RequirementGroupEntity::getProjectId, projectId)).stream().map(RequirementGroupEntity::getId)
                .toList();
        // messages 引用 agents，须在团队级删 agents 前清理；亦在 requirement_groups 之前
        if (!groupIds.isEmpty()) {
            messageMapper.delete(Wrappers.<MessageEntity>lambdaQuery()
                    .in(MessageEntity::getRequirementGroupId, groupIds));
        }

        // diffs 引用 diff_review_batches（review_batch_id），须先删 diffs（级联 diff_files/diff_comments）再删批次
        diffMapper.delete(Wrappers.<DiffEntity>lambdaQuery().eq(DiffEntity::getProjectId, projectId));
        diffReviewBatchMapper.delete(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId));

        // memory_message_sources 由 memories 外键级联删除
        memoryMapper.delete(Wrappers.<MemoryEntity>lambdaQuery().eq(MemoryEntity::getProjectId, projectId));

        // merge_requests 引用 project_repositories/tasks/workspaces，须在三者之前删除
        // （merge_request_groups/reviews、quality_check_results 由 merge_requests 外键级联）
        List<UUID> repoIds = projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream().map(ProjectRepositoryEntity::getId)
                .toList();
        if (!repoIds.isEmpty()) {
            mergeRequestMapper.delete(Wrappers.<MergeRequestEntity>lambdaQuery()
                    .in(MergeRequestEntity::getProjectRepositoryId, repoIds));
        }

        // task_runs 引用 tasks/task_steps/agents，须先于 tasks 与团队级 agents 删除
        // （execution_logs/input_requests 由 task_runs 外键级联）
        taskRunMapper.delete(Wrappers.<TaskRunEntity>lambdaQuery().eq(TaskRunEntity::getProjectId, projectId));
        // test_runs/dry_runs 引用 tasks/task_steps，须先于 tasks 删除
        testRunMapper.delete(Wrappers.<TestRunEntity>lambdaQuery().eq(TestRunEntity::getProjectId, projectId));
        dryRunMapper.delete(Wrappers.<DryRunEntity>lambdaQuery().eq(DryRunEntity::getProjectId, projectId));

        // tasks 引用 requirement_groups/workspaces/messages，须先于二者删除
        // （task_steps/task_execution_artifacts/step 依赖表由 tasks 外键级联）
        taskMapper.delete(Wrappers.<TaskEntity>lambdaQuery().eq(TaskEntity::getProjectId, projectId));
        // workspaces 引用 project；workspace_repositories 由 workspaces 外键级联
        workspaceMapper.delete(Wrappers.<WorkspaceEntity>lambdaQuery().eq(WorkspaceEntity::getProjectId, projectId));

        testsetMapper.delete(Wrappers.<TestsetEntity>lambdaQuery().eq(TestsetEntity::getProjectId, projectId));
        skillMapper.delete(Wrappers.<SkillEntity>lambdaQuery().eq(SkillEntity::getProjectId, projectId));
        attachmentMapper.delete(Wrappers.<AttachmentEntity>lambdaQuery().eq(AttachmentEntity::getProjectId, projectId));

        // requirement_groups 引用 project；rgr/group_agents 由 requirement_groups 外键级联，
        // 须在 messages/events/notifications/tasks 之后删除
        requirementGroupMapper.delete(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getProjectId, projectId));

        // project 级联 project_members/project_repositories 及后续 branch configs 等
        projectMapper.deleteById(projectId);
    }
}
