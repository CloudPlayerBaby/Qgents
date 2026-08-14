package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.InputRequestResponse;
import qg.qgent.dto.LogEntryResponse;
import qg.qgent.dto.TaskRunDetailResponse;
import qg.qgent.dto.TaskRunListItemResponse;
import qg.qgent.dto.TaskRunSummaryResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.InputRequestEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.InputRequestMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;

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
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final TaskRunService service = new TaskRunService(runs, logs, inputRequests, diffs, steps, agents,
            artifacts, access, events, mock(NotificationService.class));

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
    }

    @BeforeEach
    void stubCounts() {
        // MyBatis-Plus selectCount 返回包装 Long，默认 mock 返回 null，未拆箱 NPE；统一补 0。
        when(artifacts.selectCount(any())).thenReturn(0L);
        when(diffs.selectCount(any())).thenReturn(0L);
    }

    @Test
    void detailDerivesDurationMsFromStartedAndFinished() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10);
        run.setStartedAt(start);
        run.setFinishedAt(start.plusSeconds(10));
        when(runs.selectById(runId)).thenReturn(run);

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertEquals(10_000L, response.getDurationMs());
        assertEquals(projectId.toString(), response.getProjectId());
        assertNotNull(response.getArtifactSummary());
    }

    @Test
    void detailOmitsDurationMsWhenTimestampsIncomplete() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStartedAt(null);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectById(runId)).thenReturn(run);

        TaskRunDetailResponse response = service.detail(projectId, runId, UUID.randomUUID());

        assertNull(response.getDurationMs());
    }

    @Test
    void summaryBoundaryOmitsExecutionTimingsAndArtifacts() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TaskRunEntity run = run(projectId, runId);
        run.setStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(runs.selectList(any())).thenReturn(List.of(run));

        ApiPageResponse<TaskRunListItemResponse> page = service.listByTask(projectId, run.getTaskId(),
                UUID.randomUUID(), null, 20, "req");

        TaskRunListItemResponse item = page.getData().getFirst();
        assertEquals(runId.toString(), item.getId());
        assertEquals(run.getStatus(), item.getStatus());
        assertEquals(run.getTaskId().toString(), item.getTaskId());
        assertEquals("执行成功", item.getStatusSummary());
        assertNull(item.getStatusReason());
        assertFalse(page.getPage().getHasMore());
        assertNull(page.getPage().getNextCursor());
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

        ApiPageResponse<TaskRunListItemResponse> page = service.listByTask(projectId, run.getTaskId(),
                UUID.randomUUID(), null, 20, "req");
        TaskRunListItemResponse item = page.getData().getFirst();

        assertEquals("等待用户补充输入", item.getStatusSummary());
        assertEquals("INPUT_REQUIRED", item.getStatusReason().getCode());
        assertEquals("请补充错误密码场景", item.getStatusReason().getSummary());
        assertFalse(item.getStatusReason().isRetryable());
    }

    @Test
    void logsExposeNodeAndCursorPagination() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        when(runs.selectById(runId)).thenReturn(run(projectId, runId));
        ExecutionLogEntity first = log(runId, 1L, "sandbox", "provisioning");
        ExecutionLogEntity second = log(runId, 2L, "git", "checkout base");
        when(logs.selectList(any())).thenReturn(List.of(first, second));

        ApiPageResponse<LogEntryResponse> page = service.logs(projectId, runId, UUID.randomUUID(), null, 20, "req");

        LogEntryResponse firstItem = page.getData().getFirst();
        assertEquals(1L, firstItem.getSequence());
        assertEquals("sandbox", firstItem.getNode());
        assertEquals("provisioning", firstItem.getContent());
        assertEquals(2, page.getData().size());
        assertFalse(page.getPage().getHasMore());
        assertNull(page.getPage().getNextCursor());
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
}
