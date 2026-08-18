package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.ExecutionContextResponse;
import qg.qgent.dto.InputRequestResponse;
import qg.qgent.dto.LogEntryResponse;
import qg.qgent.dto.TaskRunDetailResponse;
import qg.qgent.dto.TaskRunListItemResponse;
import qg.qgent.dto.TaskRunSummaryResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.InputRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.InputRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** TaskRun 详情/列表字段边界与受控执行输入请求接缝测试。 */
class TaskRunServiceTest {
    private final TaskRunMapper runs = mock(TaskRunMapper.class);
    private final ExecutionLogMapper logs = mock(ExecutionLogMapper.class);
    private final InputRequestMapper inputRequests = mock(InputRequestMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final TaskExecutionArtifactMapper artifacts = mock(TaskExecutionArtifactMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final WorkspaceRepositoryMapper workspaceRepositories = mock(WorkspaceRepositoryMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final EventService events = mock(EventService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TaskRunService service = new TaskRunService(runs, logs, inputRequests, diffs, steps, agents,
            artifacts, tasks, groups, projectRepositories, workspaceRepositories, access, groupService, events,
            mock(NotificationService.class), eventPublisher);

    @BeforeAll
    static void registerTableInfos() {
        // 纯 Mockito 单元测试无 Spring/MyBatis 上下文，Wrappers.lambdaQuery 需要实体 TableInfo 缓存；
        // 手动初始化以支持 buildListItems 的批量查询构造。已注册实体幂等跳过。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TaskRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskStepEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentEntity.class);
        TableInfoHelper.initTableInfo(assistant, InputRequestEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskExecutionArtifactEntity.class);
        TableInfoHelper.initTableInfo(assistant, DiffEntity.class);
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, RequirementGroupEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceRepositoryEntity.class);
    }

    @BeforeEach
    void stubCounts() {
        // MyBatis-Plus selectCount 返回包装 Long，默认 mock 返回 null，未拆箱 NPE；统一补 0。
        when(artifacts.selectCount(any())).thenReturn(0L);
        when(diffs.selectCount(any())).thenReturn(0L);
        // 运行发起人操作校验放行（requireOwner 基于 isOwnerOrAdmin）
        when(access.isOwnerOrAdmin(any(), any(), any())).thenReturn(true);
    }

    @Test
    void detailDerivesDurationMsFromStartedAndFinished() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10);
        run.setStartedAt(start);
        run.setFinishedAt(start.plusSeconds(10));
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(any())).thenReturn(task(run));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals(10_000L, response.getDurationMs());
        assertEquals(projectId.toString(), response.getProjectId());
        assertNotNull(response.getArtifactSummary());
        assertEquals("执行成功", response.getStatusSummary());
        assertNotNull(response.getSteps());
        assertTrue(response.getSteps().isEmpty());
    }

    @Test
    void detailOmitsDurationMsWhenTimestampsIncomplete() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStartedAt(null);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertNull(response.getDurationMs());
    }

    @Test
    void failedDetailUsesSanitizedFailureFromRunArtifact() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("failureCode", "GIT_BASE_REF_NOT_FOUND",
                "message", "找不到任务指定的基线分支或提交"));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals("GIT_BASE_REF_NOT_FOUND", response.getStatusReason().getFailureCode());
        assertEquals("找不到任务指定的基线分支或提交", response.getStatusReason().getSummary());
    }

    @Test
    void summaryBoundaryOmitsExecutionTimingsAndArtifacts() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectList(any())).thenReturn(List.of(run));
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));

        ApiPageResponse<TaskRunListItemResponse> page = service.listByTask(projectId, run.getTaskId(),
                UUID.randomUUID(), null, 20, "req");

        TaskRunListItemResponse item = page.data().getFirst();
        assertEquals(runId.toString(), item.getId());
        assertEquals(run.getStatus(), item.getStatus());
        assertEquals(run.getTaskId().toString(), item.getTaskId());
        assertEquals("执行成功", item.getStatusSummary());
        assertNull(item.getStatusReason());
        assertFalse(page.page().getHasMore());
        assertNull(page.page().getNextCursor());
    }

    @Test
    void listItemDerivesStatusReasonForWaitingInput() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("WAITING_INPUT");
        when(runs.selectList(any())).thenReturn(List.of(run));
        InputRequestEntity request = new InputRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTaskRunId(runId);
        request.setKind("INPUT");
        request.setStatus("PENDING");
        request.setPrompt("请补充错误密码场景");
        request.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(inputRequests.selectList(any())).thenReturn(List.of(request));
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));

        ApiPageResponse<TaskRunListItemResponse> page = service.listByTask(projectId, run.getTaskId(),
                UUID.randomUUID(), null, 20, "req");
        TaskRunListItemResponse item = page.data().getFirst();

        assertEquals("等待用户补充输入", item.getStatusSummary());
        assertEquals("INPUT_REQUIRED", item.getStatusReason().getCode());
        assertEquals("请补充错误密码场景", item.getStatusReason().getSummary());
        assertFalse(item.getStatusReason().isRetryable());
    }

    @Test
    void logsExposeNodeAndCursorPagination() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        ExecutionLogEntity first = log(runId, 1L, "sandbox", "provisioning");
        ExecutionLogEntity second = log(runId, 2L, "git", "checkout base");
        when(logs.selectList(any())).thenReturn(List.of(first, second));

        ApiPageResponse<LogEntryResponse> page = service.logs(projectId, runId, UUID.randomUUID(), null, 20, "req");

        LogEntryResponse firstItem = page.data().getFirst();
        assertEquals(1L, firstItem.getSequence());
        assertEquals("sandbox", firstItem.getNode());
        assertEquals("provisioning", firstItem.getContent());
        assertEquals(2, page.data().size());
        assertFalse(page.page().getHasMore());
        assertNull(page.page().getNextCursor());
    }

    @Test
    void executionContextKeepsStableFieldsWhenTaskHasNoRepository() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        TaskEntity task = task(run);
        task.setWorkspaceId(UUID.randomUUID());
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task);
        when(workspaceRepositories.selectByWorkspace(task.getWorkspaceId())).thenReturn(List.of());

        ExecutionContextResponse context = service.executionContext(projectId, runId, UUID.randomUUID());

        assertEquals(task.getWorkspaceId().toString(), context.getWorkspaceId());
        assertNull(context.getRepositoryId());
        assertNull(context.getSandboxStatus());
        assertNull(context.getBaseRef());
        assertNull(context.getHeadRef());
    }

    @Test
    void inputRequestsUseStablePageEnvelopeForEmptyResult() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        when(inputRequests.selectList(any())).thenReturn(List.of());

        ApiPageResponse<InputRequestResponse> page = service.inputRequests(projectId, runId,
                UUID.randomUUID(), "req-inputs");

        assertNotNull(page.data());
        assertTrue(page.data().isEmpty());
        assertNull(page.page().getNextCursor());
        assertFalse(page.page().getHasMore());
        assertEquals("req-inputs", page.requestId());
    }

    @Test
    void createInputRequestPublishesInputRequiredAndMarksRunWaiting() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), stepId = UUID.randomUUID();
        UUID runId = UUID.randomUUID(), createdBy = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setTaskId(taskId);
        run.setTaskStepId(stepId);
        run.setStatus("RUNNING");
        when(runs.selectById(runId)).thenReturn(run);

        InputRequestResponse response = service.createInputRequest(projectId, taskId, stepId, runId,
                "INPUT", "请补充验收说明", List.of("a", "b"), createdBy);

        assertEquals("INPUT", response.getKind());
        assertEquals("PENDING", response.getStatus());
        assertEquals("WAITING_INPUT", run.getStatus());
        verify(inputRequests).insert(any(InputRequestEntity.class));
        verify(runs).updateById(run);
        verify(events).publish(eq(projectId), isNull(), eq("input-required"), any(), any(Map.class));
        verify(events).publish(eq(projectId), isNull(), eq("task-run.updated"), any(), any(Map.class));
    }

    @Test
    void createInputRequestRejectsInvalidKindAndNonRunning() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), stepId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TaskRunEntity running = run(projectId, runId);
        running.setTaskId(taskId);
        running.setTaskStepId(stepId);
        running.setStatus("RUNNING");
        when(runs.selectById(runId)).thenReturn(running);

        ApiException invalidKind = assertThrows(ApiException.class,
                () -> service.createInputRequest(projectId, taskId, stepId, runId, "FANCY", "p", null,
                        UUID.randomUUID()));
        assertEquals("INVALID_INPUT_KIND", invalidKind.code());

        TaskRunEntity queued = run(projectId, runId);
        queued.setTaskId(taskId);
        queued.setTaskStepId(stepId);
        queued.setStatus("QUEUED");
        when(runs.selectById(runId)).thenReturn(queued);
        ApiException notWaitable = assertThrows(ApiException.class,
                () -> service.createInputRequest(projectId, taskId, stepId, runId, "APPROVAL", "p", null,
                        UUID.randomUUID()));
        assertEquals("TASK_RUN_NOT_WAITABLE", notWaitable.code());
    }

    /** 重试受理：失败运行从源步骤发布续跑事件（携带 retryOfTaskRunId），不再创建无人消费的 QUEUED run。 */
    @Test
    void retryPublishesResumeEventInsteadOfOrphanQueuedRun() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        TaskRunEntity failed = run(projectId, runId);
        failed.setTaskId(taskId);
        failed.setTaskStepId(stepId);
        failed.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(failed);
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setStatus("FAILED");
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskRunSummaryResponse response = service.retry(projectId, runId, UUID.randomUUID());

        assertEquals(runId.toString(), response.getId());
        assertEquals("FAILED", response.getStatus());
        verify(runs, never()).insert(any(TaskRunEntity.class));
        verify(eventPublisher).publishEvent(argThat((TaskResumeRequestedEvent e) -> e.taskId().equals(taskId)
                && e.projectId().equals(projectId) && e.startStepId().equals(stepId)
                && e.retryOfTaskRunId().equals(runId)));
    }

    /** 任务 RUNNING（编排中）不接受外部续跑，避免与进行中的编排冲突。 */
    @Test
    void retryRejectsTaskAlreadyRunning() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskRunEntity failed = run(projectId, runId);
        failed.setTaskId(taskId);
        failed.setTaskStepId(UUID.randomUUID());
        failed.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(failed);
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException e = assertThrows(ApiException.class,
                () -> service.retry(projectId, runId, UUID.randomUUID()));

        assertEquals("TASK_NOT_RESUMABLE", e.code());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 非 FAILED/CANCELLED/BLOCKED 的运行不可重试。 */
    @Test
    void retryRejectsNonRetryableRun() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TaskRunEntity succeeded = run(projectId, runId);
        succeeded.setStatus("SUCCEEDED");
        when(runs.selectById(runId)).thenReturn(succeeded);
        when(tasks.selectById(succeeded.getTaskId())).thenReturn(task(succeeded));

        ApiException e = assertThrows(ApiException.class,
                () -> service.retry(projectId, runId, UUID.randomUUID()));

        assertEquals("TASK_RUN_NOT_RETRYABLE", e.code());
    }

    private ExecutionLogEntity log(UUID runId, long sequence, String node, String content) {
        ExecutionLogEntity entry = new ExecutionLogEntity();
        entry.setId(UUID.randomUUID());
        entry.setTaskRunId(runId);
        entry.setSequenceNo(sequence);
        entry.setNode(node);
        entry.setContent(content);
        entry.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return entry;
    }

    private TaskRunEntity run(UUID projectId, UUID runId) {
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setProjectId(projectId);
        run.setTaskId(UUID.randomUUID());
        run.setTaskStepId(UUID.randomUUID());
        run.setRole("DEVELOPER");
        run.setStatus("SUCCEEDED");
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setUpdatedAt(run.getCreatedAt());
        return run;
    }

    private TaskEntity task(TaskRunEntity run) {
        TaskEntity task = new TaskEntity();
        task.setId(run.getTaskId());
        task.setProjectId(run.getProjectId());
        return task;
    }
}
