package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.dto.TaskDetailResponse;
import qg.qgent.dto.TaskListItemResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.InputRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskAcceptanceCriterionEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.InputRequestMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TaskAcceptanceCriterionMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepDependencyMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.TaskStepRepositoryMapper;
import qg.qgent.mapper.UserMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 任务中心/任务详情展示摘要组装测试（执行统计、待处理事项、操作能力与列表分页）。 */
class TaskDisplayServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final TaskRunMapper runs = mock(TaskRunMapper.class);
    private final TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
    private final TaskStepRepositoryMapper stepRepositories = mock(TaskStepRepositoryMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final InputRequestMapper inputRequests = mock(InputRequestMapper.class);
    private final TaskExecutionArtifactMapper artifacts = mock(TaskExecutionArtifactMapper.class);
    private final DiffReviewBatchMapper diffBatches = mock(DiffReviewBatchMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final TaskAcceptanceCriterionMapper acceptanceCriteria = mock(TaskAcceptanceCriterionMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ObjectMapper json = new ObjectMapper();

    private final TaskDisplayService service = new TaskDisplayService(tasks, steps, runs, dependencies,
            stepRepositories, worktrees, workspaces, projectRepositories, githubRepositories, users, agents, groups,
            inputRequests, artifacts, diffBatches, diffs, mergeRequests, messages, acceptanceCriteria, access, json);

    @BeforeAll
    static void registerTableInfos() {
        // 纯 Mockito 单元测试无 Spring/MyBatis 上下文，Wrappers.lambdaQuery 需要实体 TableInfo 缓存。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskStepEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, InputRequestEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, UserEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentEntity.class);
        TableInfoHelper.initTableInfo(assistant, RequirementGroupEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskExecutionArtifactEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffReviewBatchEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskAcceptanceCriterionEntity.class);
    }

    @BeforeEach
    void stubDefaults() {
        when(worktrees.selectByWorkspaces(any())).thenReturn(List.of());
        when(projectRepositories.selectList(any())).thenReturn(List.of());
        when(githubRepositories.selectList(any())).thenReturn(List.of());
        when(diffBatches.selectList(any())).thenReturn(List.of());
        when(acceptanceCriteria.selectList(any())).thenReturn(List.of());
        when(artifacts.selectList(any())).thenReturn(List.of());
        when(inputRequests.selectList(any())).thenReturn(List.of());
        when(diffs.selectList(any())).thenReturn(List.of());
        when(agents.selectList(any())).thenReturn(List.of());
        when(mergeRequests.selectList(any())).thenReturn(List.of());
    }

    @Test
    void listBuildsExecutionSummaryAndAttention() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "RUNNING");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        TaskStepEntity step = step(stepId, task.getId(), "RUNNING");
        when(steps.selectList(any())).thenReturn(List.of(step));
        TaskRunEntity run = run(projectId, task.getId(), stepId, runId, "WAITING_INPUT");
        when(runs.selectList(any())).thenReturn(List.of(run));
        InputRequestEntity request = new InputRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTaskRunId(runId);
        request.setKind("INPUT");
        request.setStatus("PENDING");
        request.setPrompt("请补充异常场景说明");
        request.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(inputRequests.selectList(any())).thenReturn(List.of(request));
        UserEntity creator = new UserEntity();
        creator.setId(creatorId);
        creator.setDisplayName("陈同学");
        creator.setAvatarUrl(null);
        when(users.selectList(any())).thenReturn(List.of(creator));
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setName("登录功能");
        group.setStatus("ACTIVE");
        when(groups.selectList(any())).thenReturn(List.of(group));

        PagedApiResponse<TaskListItemResponse> page = service.list(projectId, actor, null, null, null, null, null,
                null, "req");

        TaskListItemResponse item = page.getData().getFirst();
        assertNull(page.getPage().getNextCursor());
        assertFalse(page.getPage().isHasMore());
        assertEquals("T-1024", item.getDisplayCode());
        assertEquals("陈同学", item.getCreatedByUser().getDisplayName());
        assertEquals("登录功能", item.getRequirementGroup().getName());
        assertEquals(1, item.getExecutionSummary().getRunningSteps());
        assertEquals(1, item.getExecutionSummary().getWaitingSteps());
        assertTrue(item.getExecutionSummary().isRequiresUserAction());
        assertEquals("INPUT_REQUIRED", item.getAttention().getKind());
        assertEquals("请补充异常场景说明", item.getAttention().getSummary());
        assertEquals(runId.toString(), item.getAttention().getTaskRunId());
    }

    @Test
    void attentionPrefersTaskDiffConfirmationOverWaitingRun() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "WAITING_DIFF_CONFIRMATION");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(steps.selectList(any())).thenReturn(List.of());
        when(runs.selectList(any())).thenReturn(List.of());
        UserEntity creator = new UserEntity();
        creator.setId(creatorId);
        creator.setDisplayName("陈同学");
        when(users.selectList(any())).thenReturn(List.of(creator));
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setName("登录功能");
        when(groups.selectList(any())).thenReturn(List.of(group));

        TaskListItemResponse item = service.list(projectId, actor, null, null, null, null, null, null, "req")
                .getData().getFirst();

        assertEquals("DIFF_CONFIRMATION_REQUIRED", item.getAttention().getKind());
    }

    @Test
    void capabilitiesDeriveForOwnerAndStatus() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity running = task(projectId, groupId, actor, workspaceId, "RUNNING");
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);
        when(tasks.selectById(running.getId())).thenReturn(running);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace(workspaceId));
        when(steps.selectList(any())).thenReturn(List.of(step(UUID.randomUUID(), running.getId(), "PENDING")));

        TaskDetailResponse detail = service.detail(projectId, running.getId(), actor);

        assertTrue(detail.getCapabilities().isCanCancel());
        assertTrue(detail.getCapabilities().isCanReplacePendingStepAgent());

        TaskEntity succeeded = task(projectId, groupId, actor, workspaceId, "SUCCEEDED");
        succeeded.setId(UUID.randomUUID());
        when(tasks.selectById(succeeded.getId())).thenReturn(succeeded);
        TaskDetailResponse finished = service.detail(projectId, succeeded.getId(), actor);

        assertFalse(finished.getCapabilities().isCanCancel());
        assertEquals("TASK_NOT_CANCELLABLE", finished.getCapabilities().getCancelDisabledReason());
        assertEquals("TASK_TERMINATED", finished.getCapabilities().getReplacePendingStepAgentDisabledReason());
    }

    @Test
    void detailIncludesAcceptanceCriteriaAndArtifactSummary() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, actor, workspaceId, "RUNNING");
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace(workspaceId));
        when(steps.selectList(any())).thenReturn(List.of());
        when(runs.selectList(any())).thenReturn(List.of());
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);

        TaskAcceptanceCriterionEntity criterion = new TaskAcceptanceCriterionEntity();
        criterion.setId(UUID.randomUUID());
        criterion.setTaskId(task.getId());
        criterion.setSequenceNo(1);
        criterion.setTitle("登录成功返回 JWT");
        criterion.setStatus("SATISFIED");
        when(acceptanceCriteria.selectList(any())).thenReturn(List.of(criterion));

        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setId(UUID.randomUUID());
        artifact.setTaskId(task.getId());
        artifact.setSequenceNo(1);
        artifact.setArtifactType("TESTING");
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskDetailResponse detail = service.detail(projectId, task.getId(), actor);

        assertEquals(1, detail.getAcceptanceCriteria().size());
        assertEquals("登录成功返回 JWT", detail.getAcceptanceCriteria().getFirst().getTitle());
        assertEquals("SATISFIED", detail.getAcceptanceCriteria().getFirst().getStatus());
        assertEquals(1, detail.getArtifactSummary().getTotal());
        assertEquals(1, detail.getArtifactSummary().getByType().get("TESTING"));
        assertFalse(detail.getDiffReviewSummary().isAvailable());
    }

    private TaskEntity task(UUID projectId, UUID groupId, UUID creatorId, UUID workspaceId, String status) {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(projectId);
        task.setRequirementGroupId(groupId);
        task.setWorkspaceId(workspaceId);
        task.setCreatedBy(creatorId);
        task.setTitle("登录接口实现");
        task.setDisplayCode("T-1024");
        task.setRequirement("实现账号密码登录");
        task.setStatus(status);
        task.setDeliveryMode("DIFF_FIRST");
        task.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        task.setUpdatedAt(task.getCreatedAt());
        return task;
    }

    private TaskStepEntity step(UUID stepId, UUID taskId, String status) {
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId);
        step.setTaskId(taskId);
        step.setSequenceNo(1);
        step.setTitle("后端接口开发");
        step.setInstruction("实现登录接口");
        step.setRole("DEVELOPER");
        step.setStatus(status);
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        step.setUpdatedAt(step.getCreatedAt());
        return step;
    }

    private TaskRunEntity run(UUID projectId, UUID taskId, UUID stepId, UUID runId, String status) {
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setProjectId(projectId);
        run.setTaskId(taskId);
        run.setTaskStepId(stepId);
        run.setRole("DEVELOPER");
        run.setStatus(status);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setUpdatedAt(run.getCreatedAt());
        return run;
    }

    private WorkspaceEntity workspace(UUID id) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setStatus("READY");
        return workspace;
    }
}
