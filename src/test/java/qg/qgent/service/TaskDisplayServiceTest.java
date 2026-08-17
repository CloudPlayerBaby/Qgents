package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.dto.TaskDetailResponse;
import qg.qgent.dto.TaskListItemResponse;
import qg.qgent.dto.TaskStepListItemResponse;
import qg.qgent.dto.DiffReviewSummary;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        PagedApiResponse<TaskListItemResponse> page = service.list(projectId, actor, null, null, null, null, null, null,
                null, "req");

        TaskListItemResponse item = page.data().getFirst();
        assertNull(page.page().getNextCursor());
        assertFalse(page.page().isHasMore());
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

        TaskListItemResponse item = service.list(projectId, actor, null, null, null, null, null, null, null, "req")
                .data().getFirst();

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

    @Test
    void detailExposesFirstDiffIdInDiffReviewSummary() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "WAITING_DIFF_CONFIRMATION");
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(steps.selectList(any())).thenReturn(List.of());
        when(runs.selectList(any())).thenReturn(List.of());
        when(users.selectList(any())).thenReturn(List.of());
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);

        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(UUID.randomUUID());
        batch.setProjectId(projectId);
        batch.setTaskId(task.getId());
        batch.setReviewStatus("PENDING_CONFIRMATION");
        when(diffBatches.selectList(any())).thenReturn(List.of(batch));

        DiffEntity firstRepo = new DiffEntity();
        firstRepo.setId(UUID.randomUUID());
        // 显式指定升序 projectRepositoryId，保证按仓库升序取首条 Diff 的断言确定可复现（随机 UUID 排序不稳定）。
        firstRepo.setProjectRepositoryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        firstRepo.setChangeStats(Map.of("files", 1, "additions", 2, "deletions", 1));
        DiffEntity secondRepo = new DiffEntity();
        secondRepo.setId(UUID.randomUUID());
        secondRepo.setProjectRepositoryId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        secondRepo.setChangeStats(Map.of("files", 1, "additions", 3, "deletions", 2));
        // 故意乱序返回，断言 diffId 取 projectRepositoryId 升序的第一条
        when(diffs.selectList(any())).thenReturn(List.of(secondRepo, firstRepo));

        TaskDetailResponse detail = service.detail(projectId, task.getId(), actor);

        DiffReviewSummary summary = detail.getDiffReviewSummary();
        assertThat(summary.isAvailable()).isTrue();
        assertThat(summary.getDiffId()).isEqualTo(firstRepo.getId().toString());
        assertThat(summary.getRepositoryCount()).isEqualTo(2);
        assertThat(summary.getFilesChanged()).isEqualTo(2);
        assertThat(summary.getAdditions()).isEqualTo(5);
        assertThat(summary.getDeletions()).isEqualTo(3);
    }

    @Test
    void stepsExcludesPlannerBootstrapDuringPlanning() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, UUID.randomUUID(), actor, workspaceId, "PLANNING");
        when(tasks.selectById(task.getId())).thenReturn(task);
        TaskStepEntity planner = step(UUID.randomUUID(), task.getId(), "PENDING");
        planner.setRole("PLANNER");
        when(steps.selectList(any())).thenReturn(List.of(planner));

        PagedApiResponse<TaskStepListItemResponse> page = service.steps(projectId, task.getId(), actor, "req");

        // 规划期仅存在 PLANNER bootstrap 步骤：过滤后为空步骤列表（前端以 status=PLANNING 渲染规划中）
        assertTrue(page.data().isEmpty());
        assertFalse(page.page().isHasMore());
    }

    @Test
    void planningExecutionSummaryExcludesPlannerStep() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "PLANNING");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        TaskStepEntity planner = step(UUID.randomUUID(), task.getId(), "PENDING");
        planner.setRole("PLANNER");
        when(steps.selectList(any())).thenReturn(List.of(planner));
        when(runs.selectList(any())).thenReturn(List.of());
        UserEntity creator = new UserEntity();
        creator.setId(creatorId);
        creator.setDisplayName("陈同学");
        when(users.selectList(any())).thenReturn(List.of(creator));
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setName("登录功能");
        when(groups.selectList(any())).thenReturn(List.of(group));

        TaskListItemResponse item = service.list(projectId, actor, null, null, null, null, null, null, null, "req")
                .data().getFirst();

        // PLANNER 不计入执行统计：规划期总步骤 0、无当前阶段
        assertEquals(0, item.getExecutionSummary().getTotalSteps());
        assertNull(item.getExecutionSummary().getCurrentStageTitle());
    }

    @Test
    void planningCapabilitiesDoNotOfferReplaceFromPlannerStep() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, actor, workspaceId, "PLANNING");
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace(workspaceId));
        TaskStepEntity planner = step(UUID.randomUUID(), task.getId(), "PENDING");
        planner.setRole("PLANNER");
        when(steps.selectList(any())).thenReturn(List.of(planner));
        when(runs.selectList(any())).thenReturn(List.of());
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);

        TaskDetailResponse detail = service.detail(projectId, task.getId(), actor);

        // 规划期可取消，但 PLANNER bootstrap 步骤不参与"待执行步骤"派生，不误开"可替换"
        assertTrue(detail.getCapabilities().isCanCancel());
        assertFalse(detail.getCapabilities().isCanReplacePendingStepAgent());
        assertEquals("NO_PENDING_STEP", detail.getCapabilities().getReplacePendingStepAgentDisabledReason());
        assertEquals(0, detail.getExecutionSummary().getTotalSteps());
    }

    @Test
    void listAppliesKeywordToSqlWrapperAcrossContractFields() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "RUNNING");
        when(tasks.selectList(any())).thenReturn(List.of(task));

        service.list(projectId, actor, null, null, null, null, "登录", null, null, "req");

        ArgumentCaptor<AbstractWrapper<TaskEntity, ?, ?>> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(tasks).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("lower(display_code) like lower(?)"), "应匹配任务编号");
        assertTrue(sql.contains("lower(title) like lower(?)"), "应匹配任务标题");
        assertTrue(sql.contains("lower(requirement) like lower(?)"), "应匹配需求摘要");
        assertTrue(sql.contains("requirement_groups where lower(name) like lower(?)"), "应匹配需求群名");
        assertTrue(sql.contains("users where lower(display_name) like lower(?)"), "应匹配创建人");
        assertTrue(sql.contains("project_repositories where lower(display_name) like lower(?)"), "应匹配仓库展示名");
        assertTrue(sql.contains("github_repositories where lower(name) like lower(?)"), "应匹配仓库名");
        assertTrue(sql.contains("lower(concat(owner_login, '/', name)) like lower(?)"), "应匹配仓库完整名");
        assertTrue(sql.contains("escape '\\\\'"), "LIKE 应使用转义子句");
        boolean hasLikeValue = captor.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(value -> "%登录%".equals(value));
        assertTrue(hasLikeValue, "关键词应以参数化值 %登录% 传入，不拼接进 SQL");
    }

    @Test
    void listIgnoresBlankKeyword() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), creatorId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, groupId, creatorId, workspaceId, "RUNNING");
        when(tasks.selectList(any())).thenReturn(List.of(task));

        service.list(projectId, actor, null, null, null, null, "   ", null, null, "req");

        ArgumentCaptor<AbstractWrapper<TaskEntity, ?, ?>> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(tasks).selectList(captor.capture());
        assertFalse(captor.getValue().getTargetSql().contains("like"), "空白关键词应等同于未传，不追加 LIKE 条件");
    }

    @Test
    void listRejectsKeywordLongerThan100UnicodeCharacters() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class,
                () -> service.list(projectId, actor, null, null, null, null, "长".repeat(101), null, null, "req"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("INVALID_QUERY_PARAMETER", ex.code());
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
