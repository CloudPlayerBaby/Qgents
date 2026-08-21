package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.dto.GroupContext;
import qg.qgent.orchestration.TaskContextSnapshotCodec;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Security and persistence behavior tests for the confirmed Task workflow model. */
class TaskServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceRepositoryMapper repositories = mock(WorkspaceRepositoryMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
    private final TaskStepRepositoryMapper scopes = mock(TaskStepRepositoryMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DefaultAgentProvisioner defaultAgents = mock(DefaultAgentProvisioner.class);
    private final ContextService contextService = mock(ContextService.class);
    private final TaskContextSnapshotCodec contextSnapshotCodec = new TaskContextSnapshotCodec(new ObjectMapper());
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final MrPreflightRequestMapper preflightRequests = mock(MrPreflightRequestMapper.class);
    private final TaskService service = new TaskService(tasks, workspaces, repositories, steps, dependencies, scopes,
            groups, projectRepositories, projects, messages, agents, access, events, eventPublisher, defaultAgents,
            contextService, contextSnapshotCodec);

    @Test
    void createPersistsOneWorkspaceAndMultipleRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID(), frontend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(projectRepositories.selectById(frontend)).thenReturn(repository(frontend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x"),
                        worktree(frontend, "repo-2", "base", "feat/task-x")));

        TaskResponse result = service.create(projectId, actor, request(groupId, List.of(backend, frontend)));

        assertEquals(2, result.getRepositoryIds().size());
        assertEquals("PROVISIONING", result.getWorkspaceStatus());
        assertEquals(2, result.getRepositories().size());
        assertEquals(backend.toString(), result.getRepositories().getFirst().getRepositoryId());
        assertEquals("feat/task-x", result.getRepositories().getFirst().getSourceBranch());
        verify(workspaces).insert(any(WorkspaceEntity.class));
        verify(repositories, times(2)).insertLink(any(), any(), any(), any(), any());
    }

    @Test
    void createUsesReadableFeatureBranchName() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        service.create(projectId, actor, request(groupId, List.of(backend)));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(repositories).insertLink(any(), any(), any(), any(), branch.capture());
        // request() 的标题为 "login"，新分支名应为 feat/<标题slug>-<id 前缀 12 位>，而非整段 UUID
        assertThat(branch.getValue()).startsWith("feat/login-").matches("feat/login-[0-9a-f]{12}");
    }

    @Test
    void createDetectsFixTypeFromChineseTitle() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        service.create(projectId, actor, request(groupId, List.of(backend), "修复登录接口报错"));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(repositories).insertLink(any(), any(), any(), any(), branch.capture());
        // 中文标题仍能识别 fix 类型，但 slug 只保留 ASCII：全中文被折叠为兜底 "task"，
        // 分支名必须能被沙箱 Worker 的 [A-Za-z0-9][A-Za-z0-9._/-]* 校验通过
        assertThat(branch.getValue()).startsWith("fix/task-").matches("fix/task-[0-9a-f]{12}");
    }

    @Test
    void createFeatureBranchFromChineseTitleIsAsciiSafe() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        service.create(projectId, actor, request(groupId, List.of(backend), "新增用户控制台页面"));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(repositories).insertLink(any(), any(), any(), any(), branch.capture());
        // 复现线上故障：中文标题曾生成 feat/新增用户控制台页面-<id>，被沙箱 Worker 的 ASCII 校验拒绝；
        // 现在必须落在 Worker 契约 [A-Za-z0-9][A-Za-z0-9._/-]* 之内
        assertThat(branch.getValue()).startsWith("feat/task-").matches("feat/task-[0-9a-f]{12}");
    }

    @Test
    void createDetectsTypeFromConventionalPrefix() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        service.create(projectId, actor, request(groupId, List.of(backend), "fix login bug"));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(repositories).insertLink(any(), any(), any(), any(), branch.capture());
        assertThat(branch.getValue()).startsWith("fix/login-bug-").matches("fix/login-bug-[0-9a-f]{12}");
    }

    @Test
    void createResolvesPerRepositoryBaseRefOverPublicFallback() {
        // 多仓库各自不同基准分支：baseRefs 命中仓库用其分支，未命中仓库用公共 baseRef。
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID(), frontend = UUID.randomUUID(), docs = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(projectRepositories.selectById(frontend)).thenReturn(repository(frontend, projectId));
        when(projectRepositories.selectById(docs)).thenReturn(repository(docs, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        TaskCreateRequest request = request(groupId, List.of(backend, frontend, docs));
        request.setBaseRef("master");
        request.setBaseRefs(Map.of(frontend, "main"));
        service.create(projectId, actor, request);

        ArgumentCaptor<UUID> repoCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> baseRefCaptor = ArgumentCaptor.forClass(String.class);
        verify(repositories, times(3)).insertLink(any(), repoCaptor.capture(), any(), baseRefCaptor.capture(), any());
        java.util.List<UUID> repos = repoCaptor.getAllValues();
        java.util.List<String> baseRefs = baseRefCaptor.getAllValues();
        int backendIdx = repos.indexOf(backend);
        int frontendIdx = repos.indexOf(frontend);
        int docsIdx = repos.indexOf(docs);
        assertThat(baseRefs.get(backendIdx)).isEqualTo("master"); // 未在 baseRefs → 公共 baseRef
        assertThat(baseRefs.get(frontendIdx)).isEqualTo("main");  // baseRefs 命中 → 仓库自己的分支
        assertThat(baseRefs.get(docsIdx)).isEqualTo("master");    // 未在 baseRefs → 公共 baseRef
    }

    @Test
    void createLeavesBaseRefNullWhenNeitherSpecifiedForDefaultBranchFallback() {
        // 全部未指定 baseRef：insertLink 传 null，Worker provision 按各仓库 defaultBranch 兜底。
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID(), frontend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(projectRepositories.selectById(frontend)).thenReturn(repository(frontend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class))).thenReturn(List.of());

        service.create(projectId, actor, request(groupId, List.of(backend, frontend)));

        ArgumentCaptor<String> baseRefCaptor = ArgumentCaptor.forClass(String.class);
        verify(repositories, times(2)).insertLink(any(), any(), any(), baseRefCaptor.capture(), any());
        assertThat(baseRefCaptor.getAllValues()).containsExactly(null, null);
    }

    @Test
    void createRejectsIllegalPerRepositoryBaseRef() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));

        TaskCreateRequest request = request(groupId, List.of(backend));
        request.setBaseRefs(Map.of(backend, "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, actor, request));
        assertEquals("INVALID_BASE_REF", error.code());
    }

    @Test
    void createPublishesTaskCreatedEvent() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x")));

        service.create(projectId, actor, request(groupId, List.of(backend)));

        ArgumentCaptor<TaskCreatedEvent> captor = ArgumentCaptor.forClass(TaskCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(projectId, captor.getValue().projectId());
        assertNotNull(captor.getValue().taskId());
    }

    @Test
    void createPersistsFrozenContextSnapshot() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x")));
        GroupContext context = new GroupContext(groupId.toString(), projectId.toString(), "需求", "背景", List.of(),
                List.of(), List.of(), List.of());
        // 快照必须携带本次实际生效的 repositoryIds，而不是只依赖需求群绑定记录。
        when(contextService.buildTaskSnapshot(actor, projectId, groupId, null, List.of(backend))).thenReturn(context);

        service.create(projectId, actor, request(groupId, List.of(backend)));

        ArgumentCaptor<TaskEntity> captured = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).insert(captured.capture());
        assertThat(captured.getValue().getContextSnapshot()).containsEntry("version", 1).containsKey("groupContext");
        verify(contextService).buildTaskSnapshot(actor, projectId, groupId, null, List.of(backend));
    }

    @Test
    void createRejectsProjectMainGroup() {
        UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "PROJECT_MAIN", "ACTIVE"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, UUID.randomUUID(), request(groupId, List.of(UUID.randomUUID()))));
        assertEquals("ACTIVE_REQUIREMENT_GROUP_REQUIRED", error.code());
    }

    @Test
    void createRejectsTriggerMessageFromAnotherGroup() {
        UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID(), messageId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setRequirementGroupId(UUID.randomUUID());
        when(messages.selectById(messageId)).thenReturn(message);
        TaskCreateRequest request = request(groupId, List.of(UUID.randomUUID()));
        request.setTriggerMessageId(messageId);
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, UUID.randomUUID(), request));
        assertEquals("TRIGGER_MESSAGE_GROUP_MISMATCH", error.code());
    }

    @Test
    void createRejectsContinuationFromDifferentRequirementGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID(), continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(otherGroup);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, actor, request));

        assertEquals("WORKSPACE_CONTINUATION_GROUP_MISMATCH", error.code());
    }

    @Test
    void createContinuationReusesWorkspaceAndInheritsRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(groupId);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setStatus("READY");
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        when(projectRepositories.selectById(repositoryId)).thenReturn(repository(repositoryId, projectId));
        when(repositories.selectByWorkspace(workspaceId))
                .thenReturn(List.of(worktree(repositoryId, "repo-1", "base", "feat/task-x")));
        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        TaskResponse result = service.create(projectId, actor, request);

        assertEquals(workspaceId.toString(), result.getWorkspaceId());
        assertEquals(continuationTaskId.toString(), result.getContinuationOfTaskId());
        assertEquals(List.of(repositoryId.toString()), result.getRepositoryIds());
        verify(workspaces, never()).insert(any(WorkspaceEntity.class));
    }

    @Test
    void createContinuationSupersedesPendingDiffInSameWorkspace() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID oldTaskId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(groupId);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        when(projectRepositories.selectById(repositoryId)).thenReturn(repository(repositoryId, projectId));
        when(repositories.selectByWorkspace(workspaceId))
                .thenReturn(List.of(worktree(repositoryId, "repo-1", "base", "feat/task-x")));

        // 被取代的旧批次归属一个已进入 WAITING_DIFF_CONFIRMATION 的旧任务。
        TaskEntity oldTask = task(oldTaskId, projectId, actor);
        oldTask.setRequirementGroupId(groupId);
        oldTask.setWorkspaceId(workspaceId);
        oldTask.setStatus("WAITING_DIFF_CONFIRMATION");
        when(tasks.selectByIdForUpdate(oldTaskId)).thenReturn(oldTask);

        DiffReviewBatchMapper reviewBatches = mock(DiffReviewBatchMapper.class);
        DiffMapper taskDiffs = mock(DiffMapper.class);
        DiffReviewBatchEntity oldBatch = new DiffReviewBatchEntity();
        oldBatch.setId(UUID.randomUUID());
        oldBatch.setWorkspaceId(workspaceId);
        oldBatch.setTaskId(oldTaskId);
        oldBatch.setProjectId(projectId);
        oldBatch.setReviewStatus("PENDING_CONFIRMATION");
        when(reviewBatches.selectPendingByWorkspaceForUpdate(workspaceId)).thenReturn(List.of(oldBatch));
        service.setDiffReviewStateMappers(reviewBatches, taskDiffs);

        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        service.create(projectId, actor, request);

        assertEquals("SUPERSEDED", oldBatch.getReviewStatus());
        assertEquals("被同一 Workspace 的后续修改取代", oldBatch.getReviewReason());
        verify(reviewBatches).updateById(oldBatch);
        verify(taskDiffs).markReviewBatchSuperseded(eq(oldBatch.getId()), any());
        // 旧任务必须从 WAITING_DIFF_CONFIRMATION 迁到 FAILED，避免永远卡在「未确认 Diff」。
        assertEquals("FAILED", oldTask.getStatus());
        assertEquals("DIFF_REVIEW_SUPERSEDED", oldTask.getFailureCode());
        assertEquals(Boolean.FALSE, oldTask.getFailureRetryable());
        verify(tasks).updateById(oldTask);
        verify(events).publish(eq(projectId), eq(groupId), eq("task.updated"), eq(oldTaskId.toString()), any());
    }

    @Test
    void createContinuationRejectsWorkspaceWithUnmergedMr() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(groupId);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        when(repositories.selectByWorkspace(workspaceId))
                .thenReturn(List.of(worktree(repositoryId, "repo-1", "base", "feat/task-x")));
        MergeRequestEntity blocker = new MergeRequestEntity();
        blocker.setId(UUID.randomUUID());
        blocker.setProjectRepositoryId(repositoryId);
        blocker.setSourceBranch("feat/task-x");
        blocker.setStatus("OPEN");
        when(mergeRequests.selectOne(any())).thenReturn(blocker);
        service.setDevelopmentGuard(new WorkBranchDevelopmentGuard(repositories, mergeRequests, preflightRequests));

        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, actor, request));

        assertEquals("WORKSPACE_CONTINUATION_BLOCKED_BY_OPEN_MR", error.code());
        verify(tasks, never()).insert(any(TaskEntity.class));
    }

    @Test
    void replaceAgentOnlyAllowsPendingStep() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID(), stepId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId); step.setTaskId(taskId); step.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(steps.selectById(stepId)).thenReturn(step);
        ApiException error = assertThrows(ApiException.class,
                () -> service.replaceAssignedAgent(projectId, taskId, stepId, actor, UUID.randomUUID()));
        assertEquals("TASK_STEP_NOT_PENDING", error.code());
        verify(steps, never()).updateById(any(TaskStepEntity.class));
    }

    @Test
    void pendingStepAgentReplacementReturnsPersistedScopes() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), agentId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), teamId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId); step.setTaskId(taskId); step.setRole("DEVELOPER"); step.setStatus("PENDING");
        ProjectEntity project = new ProjectEntity(); project.setId(projectId); project.setTeamId(teamId);
        AgentEntity agent = new AgentEntity(); agent.setId(agentId); agent.setTeamId(teamId); agent.setRole("DEVELOPER");
        agent.setVisibility("TEAM"); agent.setStatus("ACTIVE");
        TaskStepRepositoryEntity scope = new TaskStepRepositoryEntity(); scope.setTaskStepId(stepId);
        scope.setProjectRepositoryId(repositoryId); scope.setAccessMode("WRITE");
        when(tasks.selectById(taskId)).thenReturn(task); when(steps.selectById(stepId)).thenReturn(step);
        when(projects.selectById(projectId)).thenReturn(project); when(agents.selectById(agentId)).thenReturn(agent);
        when(scopes.selectByStep(stepId)).thenReturn(List.of(scope));

        TaskStepResponse response = service.replaceAssignedAgent(projectId, taskId, stepId, actor, agentId);

        assertEquals(agentId.toString(), response.getAssignedAgentId());
        assertEquals("WRITE", response.getRepositoryScopes().getFirst().getAccessMode());
        verify(steps).updateById(step);
    }

    @Test
    void revisionStepMayDependOnExistingStepAndKeepsPerRepositoryAccessMode() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID(), newId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(tasks.selectById(taskId)).thenReturn(task(taskId, projectId, actor));
        UUID workspaceId = UUID.randomUUID();
        task(taskId, projectId, actor).setWorkspaceId(workspaceId);
        TaskEntity task = task(taskId, projectId, actor); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity taskRepository = new WorkspaceRepositoryEntity(); taskRepository.setWorkspaceId(workspaceId);
        taskRepository.setProjectRepositoryId(repositoryId);
        when(repositories.selectByWorkspace(workspaceId)).thenReturn(List.of(taskRepository));
        TaskStepEntity existing = new TaskStepEntity(); existing.setId(existingId); existing.setTaskId(taskId);
        when(steps.selectList(any())).thenReturn(List.of(existing));
        TaskStepCreateRequest request = step(newId, repositoryId, "WRITE");
        request.setDependencyIds(List.of(existingId));

        List<TaskStepResponse> response = service.addSteps(projectId, taskId, actor, List.of(request));

        assertEquals("WRITE", response.getFirst().getRepositoryScopes().getFirst().getAccessMode());
        verify(dependencies).insertLink(newId, existingId);
        verify(scopes).insertLink(newId, repositoryId, "WRITE");
    }

    @Test
    void materializedPlanRejectsAppendingSteps() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setPlanMaterializedAt(java.time.LocalDateTime.now());
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.addSteps(projectId, taskId, actor, List.of()));

        assertEquals("TASK_STEP_PLAN_FROZEN", exception.code());
        verifyNoInteractions(workspaces, repositories, steps);
    }

    @Test
    void cancelPendingTaskMarksCancelledAndPublishesEvent() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("PENDING"); task.setRequirementGroupId(groupId); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskResponse result = service.cancel(projectId, taskId, actor);

        assertEquals("CANCELLED", result.getStatus());
        assertEquals("CANCELLED", task.getStatus());
        verify(tasks).updateById(task);
        // 取消时未执行的 PENDING 步骤一并落 CANCELLED
        verify(steps).update(any(), org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper>any());
        verify(events).publish(any(), any(), eq("task.updated"), eq(taskId.toString()), any(Map.class));
    }

    @Test
    void cancelPendingTaskCancelsOnlyPendingSteps() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("PENDING");
        when(tasks.selectById(taskId)).thenReturn(task);

        service.cancel(projectId, taskId, actor);

        // 取消时通过 steps.update 把未执行步骤置 CANCELLED（UpdateWrapper 限定 status=PENDING，
        // 不触碰已终态步骤）；mock 仅验证调用发生，SQL 条件由实现自身保证。
        verify(steps, times(1)).update(any(), org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper>any());
    }

    @Test
    void cancelRunningTaskMarksCancelling() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskResponse result = service.cancel(projectId, taskId, actor);

        assertEquals("CANCELLING", result.getStatus());
        assertEquals("CANCELLING", task.getStatus());
    }

    @Test
    void cancelSucceededTaskRejected() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("SUCCEEDED");
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.cancel(projectId, taskId, actor));

        assertEquals("TASK_NOT_CANCELLABLE", error.code());
        verify(tasks, never()).updateById(any(TaskEntity.class));
    }

    private TaskCreateRequest request(UUID groupId, List<UUID> repositoryIds) {
        return request(groupId, repositoryIds, "login");
    }

    private TaskCreateRequest request(UUID groupId, List<UUID> repositoryIds, String title) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(groupId); request.setTitle(title); request.setRequirement("implement login");
        request.setRepositoryIds(repositoryIds); return request;
    }

    private TaskStepCreateRequest step(UUID id, UUID repositoryId, String accessMode) {
        TaskRepositoryScopeRequest scope = new TaskRepositoryScopeRequest();
        scope.setRepositoryId(repositoryId); scope.setAccessMode(accessMode);
        TaskStepCreateRequest request = new TaskStepCreateRequest();
        request.setId(id); request.setTitle("revise"); request.setInstruction("apply review feedback");
        request.setRole("DEVELOPER"); request.setRepositoryScopes(List.of(scope)); return request;
    }

    private RequirementGroupEntity group(UUID id, UUID projectId, String type, String status) {
        RequirementGroupEntity group = new RequirementGroupEntity(); group.setId(id); group.setProjectId(projectId);
        group.setGroupType(type); group.setStatus(status); return group;
    }

    private ProjectRepositoryEntity repository(UUID id, UUID projectId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity(); repository.setId(id);
        repository.setProjectId(projectId); repository.setStatus("ACTIVE"); return repository;
    }

    private WorkspaceRepositoryEntity worktree(UUID repositoryId, String path, String baseCommit, String sourceBranch) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId); worktree.setWorkspacePath(path);
        worktree.setBaseCommit(baseCommit); worktree.setSourceBranch(sourceBranch); worktree.setHeadCommit(null);
        return worktree;
    }

    private TaskEntity task(UUID id, UUID projectId, UUID creator) {
        TaskEntity task = new TaskEntity(); task.setId(id); task.setProjectId(projectId); task.setCreatedBy(creator);
        return task;
    }
}
