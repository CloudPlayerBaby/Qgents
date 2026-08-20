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
import qg.qgent.dto.TaskRunDiagnosticsResponse;
import qg.qgent.dto.TaskRunListItemResponse;
import qg.qgent.dto.TaskRunSummaryResponse;
import qg.qgent.dto.TaskDiagnosticsResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.InputRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskRunWorkerExecutionEntity;
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
import qg.qgent.mapper.TaskRunWorkerExecutionMapper;
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
    void failedDetailDoesNotExposeAgentMessage() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("failureCode", "CODING_NO_ACTUAL_CHANGE",
                "message", "coding agent failed: CODING_NO_ACTUAL_CHANGE: internal model details"));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals("CODING_NO_ACTUAL_CHANGE", response.getStatusReason().getFailureCode());
        assertEquals("代码步骤未产生实际文件变更", response.getStatusReason().getSummary());
        assertFalse(response.getStatusReason().getSummary().contains("internal model details"));
        assertTrue(response.getStatusReason().isRetryable());
    }

    @Test
    void failedDetailUsesGenericMessageWhenHistoricalCodeIsMissing() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("message", "raw exception from model"));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertNull(response.getStatusReason().getFailureCode());
        assertEquals("任务执行失败，请查看执行记录", response.getStatusReason().getSummary());
        assertFalse(response.getStatusReason().isRetryable());
    }

    @Test
    void diagnosticsReturnsMainFailureAndStructuredWorkerExecutions() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID(), executionId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        run.setFailureCode("FILE_PATCH_FAILED");
        run.setFailureReason("补丁上下文与文件不一致");
        run.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskRunWorkerExecutionMapper workerExecutions = mock(TaskRunWorkerExecutionMapper.class);
        TaskRunWorkerExecutionEntity worker = new TaskRunWorkerExecutionEntity();
        worker.setExecutionId(executionId);
        worker.setProjectId(projectId);
        worker.setTaskId(run.getTaskId());
        worker.setTaskRunId(runId);
        worker.setToolName("file.patch");
        worker.setStatus("FAILED");
        worker.setFailureCode("FILE_PATCH_FAILED");
        worker.setFailureReason("补丁上下文与文件不一致");
        worker.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        TaskRunWorkerExecutionEntity successfulWorker = new TaskRunWorkerExecutionEntity();
        successfulWorker.setExecutionId(UUID.randomUUID());
        successfulWorker.setProjectId(projectId);
        successfulWorker.setTaskId(run.getTaskId());
        successfulWorker.setTaskRunId(runId);
        successfulWorker.setToolName("file.read");
        successfulWorker.setStatus("SUCCEEDED");
        when(workerExecutions.selectList(any())).thenReturn(List.of(successfulWorker, worker));
        TaskRunService diagnosticService = new TaskRunService(runs, logs, inputRequests, diffs, steps, agents,
                artifacts, tasks, groups, projectRepositories, workspaceRepositories, access, groupService, events,
                mock(NotificationService.class), eventPublisher,
                new TaskRunLogService(logs, tasks, runs, events), workerExecutions);

        TaskRunDiagnosticsResponse response = diagnosticService.diagnostics(projectId, runId, UUID.randomUUID());

        assertEquals("CODING", response.getStage());
        assertEquals("FILE_PATCH_FAILED", response.getFailure().getFailureCode());
        assertEquals("补丁上下文与文件不一致", response.getFailure().getSummary());
        assertEquals(executionId.toString(), response.getWorkerExecutions().getFirst().getExecutionId());
        assertEquals(1, response.getWorkerExecutions().size());
    }

    @Test
    void taskDiagnosticsWorksWhenFailureHappensBeforeTaskRunExists() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity failedTask = new TaskEntity();
        failedTask.setId(taskId);
        failedTask.setProjectId(projectId);
        failedTask.setStatus("FAILED");
        failedTask.setFailureCode("SANDBOX_WORKER_UNAVAILABLE");
        failedTask.setFailureReason("执行环境暂时不可用");
        failedTask.setFailureRetryable(true);
        failedTask.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        when(tasks.selectById(taskId)).thenReturn(failedTask);
        when(runs.selectList(any())).thenReturn(List.of());

        TaskDiagnosticsResponse response = service.taskDiagnostics(projectId, taskId, UUID.randomUUID());

        assertEquals(taskId.toString(), response.getTaskId());
        assertEquals("PLANNING", response.getStage());
        assertNull(response.getLatestFailedRun());
        assertEquals("SANDBOX_WORKER_UNAVAILABLE", response.getFailure().getFailureCode());
        // 稳定码统一映射受控文案（不回显持久化原文，防止旧数据泄漏内部细节）
        assertEquals("Sandbox Worker 当前不可用", response.getFailure().getSummary());
    }

    @Test
    void taskDiagnosticsSuppressesFailureWhileTaskIsRunningQualityFixLoop() {
        // Test/Review 质量失败退回 Developer 修复期间任务仍为 RUNNING：历史上 FAILED 的
        // TaskRun 只是修复循环中的一次失败，不应作为「任务失败」诊断返回给前端。
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity runningTask = new TaskEntity();
        runningTask.setId(taskId);
        runningTask.setProjectId(projectId);
        runningTask.setStatus("RUNNING");
        TaskRunEntity failedRun = run(projectId, UUID.randomUUID());
        failedRun.setTaskId(taskId);
        failedRun.setStatus("FAILED");
        failedRun.setFailureCode("TEST_FAILED");
        failedRun.setFailureReason("测试未通过");
        failedRun.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        when(tasks.selectById(taskId)).thenReturn(runningTask);
        when(runs.selectList(any())).thenReturn(List.of(failedRun));

        TaskDiagnosticsResponse response = service.taskDiagnostics(projectId, taskId, UUID.randomUUID());

        assertNull(response.getFailure());
        assertNull(response.getLatestFailedRun());
    }

    @Test
    void detailProjectsPersistedObservationsIntoInternalNodes() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("observations", List.of(
                Map.of("phase", "CODING", "round", 1, "promptChars", 120,
                        "responseChars", 80, "responseSha256", "not-a-node-field"),
                Map.of("phase", "CODING", "round", 2, "promptChars", 140,
                        "responseChars", 60, "toolName", "write_file"))));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals(2, response.getSteps().size());
        assertEquals("CODING#round-1", response.getSteps().get(0).getNode());
        assertEquals("PASSED", response.getSteps().get(0).getStatus());
        assertNull(response.getSteps().get(0).getErrorCode());
        assertEquals("CODING#round-2", response.getSteps().get(1).getNode());
        // DTO 只暴露节点状态，不携带观测中的 prompt/响应长度、工具名或哈希。
        assertNull(response.getSteps().get(1).getStartedAt());
        assertNull(response.getSteps().get(1).getFinishedAt());
        assertNull(response.getSteps().get(1).getDurationMs());
    }

    @Test
    void detailMarksProtocolFailureObservationAsFailed() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("observations", List.of(
                Map.of("phase", "CODING", "round", 3,
                        "protocolFailureCode", "LLM_FINISH_LENGTH"))));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals(1, response.getSteps().size());
        assertEquals("CODING#round-3", response.getSteps().getFirst().getNode());
        assertEquals("FAILED", response.getSteps().getFirst().getStatus());
        assertEquals("LLM_FINISH_LENGTH", response.getSteps().getFirst().getErrorCode());
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
    void failedLogsExposeTerminalSummaryWhenExecutorDidNotPersistLogs() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("FAILED");
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectById(runId)).thenReturn(run);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setTaskRunId(runId);
        artifact.setSequenceNo(1);
        artifact.setSummary(Map.of("failureCode", "EXECUTION_FAILED",
                "message", "工具集缺少目录创建能力"));
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));
        when(logs.selectList(any())).thenReturn(List.of());

        ApiPageResponse<LogEntryResponse> page = service.logs(projectId, runId, UUID.randomUUID(), null, 20, "req");

        assertEquals(1, page.data().size());
        assertEquals("DEVELOPER", page.data().getFirst().getNode());
        assertTrue(page.data().getFirst().getContent().contains("任务执行失败，请查看执行记录"));
        assertEquals(1L, page.data().getFirst().getSequence());
        assertFalse(page.page().getHasMore());
    }

    @Test
    void workerToolLogDoesNotExposeWorkerFailureReason() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        when(logs.nextSequence(runId)).thenReturn(0L);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));
        qg.qgent.orchestration.worker.WorkerToolExecution execution =
                new qg.qgent.orchestration.worker.WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setTool("file.patch");
        execution.setStatus("FAILED");
        execution.setFailureCode("FILE_PATCH_FAILED");
        execution.setFailureReason("补丁上下文与文件不一致（第 7 行），模型原始细节");

        service.appendWorkerToolExecution(run, execution);

        var log = org.mockito.ArgumentCaptor.forClass(ExecutionLogEntity.class);
        verify(logs).insert(log.capture());
        assertTrue(log.getValue().getContent().contains("failureCode=FILE_PATCH_FAILED"));
        assertTrue(log.getValue().getContent().contains("补丁无法应用，请重新读取文件后重试"));
        assertFalse(log.getValue().getContent().contains("第 7 行"));
        assertFalse(log.getValue().getContent().contains("模型原始细节"));
    }

    @Test
    void workerToolLogDoesNotExposeSuccessfulExecutionId() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        qg.qgent.orchestration.worker.WorkerToolExecution execution =
                new qg.qgent.orchestration.worker.WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setTool("file.read");
        execution.setStatus("SUCCEEDED");

        service.appendWorkerToolExecution(run, execution);

        verifyNoInteractions(logs);
    }

    @Test
    void terminalLogDoesNotExposeAgentFailureDetail() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStatus("RUNNING");
        when(runs.selectById(runId)).thenReturn(run);
        when(logs.nextSequence(runId)).thenReturn(0L);
        when(tasks.selectById(run.getTaskId())).thenReturn(task(run));

        service.complete(runId, "FAILED", "CODING_NO_ACTUAL_CHANGE",
                "coding agent failed: internal model error");

        var log = org.mockito.ArgumentCaptor.forClass(ExecutionLogEntity.class);
        verify(logs).insert(log.capture());
        assertTrue(log.getValue().getContent().contains("CODING_NO_ACTUAL_CHANGE"));
        assertTrue(log.getValue().getContent().contains("代码步骤未产生实际文件变更"));
        assertFalse(log.getValue().getContent().contains("internal model error"));
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
