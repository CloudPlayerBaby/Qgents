package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qg.qgent.entity.AgentEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.entity.AttachmentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.EventEntity;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MemoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.NotificationEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.AttachmentMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.EventMapper;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MemoryMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.NotificationMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.SkillMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.mapper.TestsetMapper;
import qg.qgent.mapper.WorkspaceMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamDisbandServiceTest {

    /**
     * 纯单元测试未启动 MyBatis/Spring，MyBatis-Plus 的 lambda 包装器在裸 JVM 下
     * 无法通过懒初始化拿到实体列缓存；这里显式注册全部涉及实体的 TableInfo，
     * 与生产环境由 MyBatis 启动时注册的行为保持一致，保证包装器构造确定性。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 与生产配置 type-handlers-package: qg.qgent.handler 对齐，注册 UUID 类型处理器
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : List.of(ProjectEntity.class, NotificationEntity.class, EventEntity.class,
                RequirementGroupEntity.class, MessageEntity.class, DiffEntity.class, DiffReviewBatchEntity.class,
                MemoryEntity.class, ProjectRepositoryEntity.class, MergeRequestEntity.class, TaskRunEntity.class,
                TestRunEntity.class, DryRunEntity.class, TaskEntity.class, WorkspaceEntity.class,
                TestsetEntity.class, SkillEntity.class, AttachmentEntity.class, GitHubRepositoryEntity.class,
                GitHubInstallationEntity.class, AgentEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final RequirementGroupMapper requirementGroupMapper = mock(RequirementGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final DiffMapper diffMapper = mock(DiffMapper.class);
    private final DiffReviewBatchMapper diffReviewBatchMapper = mock(DiffReviewBatchMapper.class);
    private final MemoryMapper memoryMapper = mock(MemoryMapper.class);
    private final ProjectRepositoryMapper projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final TaskRunMapper taskRunMapper = mock(TaskRunMapper.class);
    private final TestRunMapper testRunMapper = mock(TestRunMapper.class);
    private final DryRunMapper dryRunMapper = mock(DryRunMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final TestsetMapper testsetMapper = mock(TestsetMapper.class);
    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final GitHubRepositoryMapper githubRepositoryMapper = mock(GitHubRepositoryMapper.class);
    private final GitHubInstallationMapper githubInstallationMapper = mock(GitHubInstallationMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamDisbandService service = new TeamDisbandService(projectMapper, notificationMapper,
            eventMapper, requirementGroupMapper, messageMapper, diffMapper, diffReviewBatchMapper, memoryMapper,
            projectRepositoryMapper, mergeRequestMapper, taskRunMapper, testRunMapper, dryRunMapper, taskMapper,
            workspaceMapper, testsetMapper, skillMapper, attachmentMapper, githubRepositoryMapper,
            githubInstallationMapper, agentMapper, teamMapper);

    @Test
    void deleteTeamCleansProjectsInDependencyOrder() {
        UUID teamId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(requirementGroupMapper.selectList(any())).thenReturn(List.of(group));

        ProjectRepositoryEntity repo = new ProjectRepositoryEntity();
        repo.setId(repoId);
        repo.setProjectId(projectId);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(repo));

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setTeamId(teamId);
        when(githubInstallationMapper.selectList(any())).thenReturn(List.of(installation));

        service.deleteTeam(teamId);

        // 关键依赖顺序：子表先于父表，引用行先于被引用行；agents 在项目级引用清理后最后删除。
        // 自引用外键（messages/tasks/task_runs/dry_runs）逐层叶子删除在各自位置完成；
        // github_repositories 引用 github_installations（fk_ghr_install 无级联），必须先删镜像再删安装记录。
        var order = inOrder(notificationMapper, eventMapper, messageMapper, diffMapper, diffReviewBatchMapper,
                memoryMapper, mergeRequestMapper, taskRunMapper, testRunMapper, dryRunMapper, taskMapper,
                workspaceMapper, testsetMapper, skillMapper, attachmentMapper, requirementGroupMapper, projectMapper,
                githubRepositoryMapper, githubInstallationMapper, agentMapper, teamMapper);
        order.verify(notificationMapper).delete(any());
        order.verify(eventMapper).delete(any());
        order.verify(diffMapper).delete(any());
        order.verify(diffReviewBatchMapper).delete(any());
        order.verify(memoryMapper).delete(any());
        order.verify(mergeRequestMapper).delete(any());
        order.verify(taskRunMapper).deleteUnreferencedRuns(any());
        order.verify(testRunMapper).delete(any());
        order.verify(dryRunMapper).deleteUnreferencedDryRuns(any());
        order.verify(taskMapper).deleteUnreferencedTasks(any());
        order.verify(messageMapper).deleteUnreferencedMessages(any());
        order.verify(workspaceMapper).delete(any());
        order.verify(testsetMapper).delete(any());
        order.verify(skillMapper).delete(any());
        order.verify(attachmentMapper).delete(any());
        order.verify(requirementGroupMapper).delete(any());
        order.verify(projectMapper).deleteById(projectId);
        order.verify(githubRepositoryMapper).delete(any());
        order.verify(githubInstallationMapper).delete(any());
        order.verify(agentMapper).delete(any());
        order.verify(teamMapper).deleteById(teamId);
    }

    /**
     * 自引用外键（如 task_runs.retry_of_task_run_id）允许形成多级重试链，
     * 单条批量 DELETE 无法一次性删除互相引用的父子行。验证叶子删除循环会
     * 持续迭代直到返回 0（本例 3 级链：逐层返回 2 → 1 → 0），而不是只删一次。
     */
    @Test
    void deleteTeamDrainsMultiLevelRetryChainUntilEmpty() {
        UUID teamId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(requirementGroupMapper.selectList(any())).thenReturn(List.of(group));

        ProjectRepositoryEntity repo = new ProjectRepositoryEntity();
        repo.setId(repoId);
        repo.setProjectId(projectId);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(repo));

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setTeamId(teamId);
        when(githubInstallationMapper.selectList(any())).thenReturn(List.of(installation));

        // 3 级重试链：叶子逐层抽干，deleteUnreferencedRuns 依次返回 2 -> 1 -> 0
        when(taskRunMapper.deleteUnreferencedRuns(any())).thenReturn(2, 1, 0);

        service.deleteTeam(teamId);

        // 循环必须在返回 0 时结束，证明自引用链被完整抽干而非仅删除一次
        verify(taskRunMapper, times(3)).deleteUnreferencedRuns(projectId);
    }
}
