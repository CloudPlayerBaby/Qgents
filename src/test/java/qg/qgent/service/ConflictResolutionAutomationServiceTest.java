package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.dto.TaskCreateRequest;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.event.DryRunConflictCandidateDomainEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConflictResolutionAutomationServiceTest {
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final TaskService taskService = mock(TaskService.class);
    private final ConflictResolutionAutomationService service =
            new ConflictResolutionAutomationService(dryRuns, tasks, worktrees, mergeRequests, taskService, 3);

    private final UUID projectId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID dryRunId = UUID.randomUUID();
    private final String head = "a".repeat(40);
    private final String target = "b".repeat(40);

    /**
     * 纯单元测试未启动 MyBatis/Spring，lambda 包装器依赖实体 TableInfo；显式注册涉及实体，
     * 避免裸 JVM 下懒初始化列缓存的行为差异导致测试偶发失败。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, MergeRequestEntity.class);
        TableInfoHelper.initTableInfo(assistant, DryRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceRepositoryEntity.class);
    }

    @BeforeEach
    void noBlockingMrByDefault() {
        when(mergeRequests.selectCount(any())).thenReturn(0L);
    }

    @Test
    void conflictDryRunSpawnsContinuationTaskWithConflictFiles() {
        TaskEntity task = task();
        when(dryRuns.selectById(dryRunId)).thenReturn(conflictDryRun());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree()));
        when(tasks.selectCount(any())).thenReturn(0L);

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        ArgumentCaptor<TaskCreateRequest> captor = ArgumentCaptor.forClass(TaskCreateRequest.class);
        verify(taskService).create(eq(projectId), eq(task.getCreatedBy()), captor.capture());
        TaskCreateRequest request = captor.getValue();
        assertEquals(taskId, request.getContinuationOfTaskId());
        assertEquals(workspaceId, request.getWorkspaceId());
        assertEquals(task.getRequirementGroupId(), request.getRequirementGroupId());
        assertEquals("MR_FIRST", request.getDeliveryMode());
        assertTrue(request.getRequirement().contains("合并冲突"));
        assertTrue(request.getRequirement().contains("- src/a.java"));
        assertTrue(request.getRequirement().contains(head));
    }

    @Test
    void activeContinuationSuppressesDuplicateSpawn() {
        TaskEntity task = task();
        when(dryRuns.selectById(dryRunId)).thenReturn(conflictDryRun());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree()));
        when(tasks.selectCount(any())).thenReturn(1L);

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void nonMergedMrSuppressesAutoContinuation() {
        TaskEntity task = task();
        when(dryRuns.selectById(dryRunId)).thenReturn(conflictDryRun());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree()));
        when(mergeRequests.selectCount(any())).thenReturn(1L);

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void continuationCapSuppressesFurtherSpawns() {
        TaskEntity task = task();
        when(dryRuns.selectById(dryRunId)).thenReturn(conflictDryRun());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree()));
        // 活动续跑 0 条；总续跑数达到上限 3。
        when(tasks.selectCount(any())).thenReturn(0L, 3L);

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void transientFailureDoesNotSpawnContinuation() {
        TaskEntity task = task();
        DryRunEntity transientFailure = conflictDryRun();
        transientFailure.setReport(Map.of("failureCode", "EXECUTION_FAILED", "message", "worker unavailable"));
        when(dryRuns.selectById(dryRunId)).thenReturn(transientFailure);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree()));

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        verify(taskService, never()).create(any(), any(), any());
    }

    @Test
    void staleConflictForAdvancedHeadDoesNotSpawn() {
        TaskEntity task = task();
        when(dryRuns.selectById(dryRunId)).thenReturn(conflictDryRun());
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity advanced = worktree();
        advanced.setHeadCommit("c".repeat(40));
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(advanced));

        service.onConflict(new DryRunConflictCandidateDomainEvent(projectId, dryRunId, taskId));

        verify(taskService, never()).create(any(), any(), any());
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setRequirementGroupId(UUID.randomUUID()); task.setTitle("实现登录");
        task.setDeliveryMode("MR_FIRST"); task.setStatus("WAITING_PREFLIGHT");
        task.setCreatedBy(UUID.randomUUID());
        return task;
    }

    private WorkspaceRepositoryEntity worktree() {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feat/login"); worktree.setHeadCommit(head); worktree.setBaseRef("main");
        return worktree;
    }

    private DryRunEntity conflictDryRun() {
        DryRunEntity run = new DryRunEntity();
        run.setId(dryRunId); run.setProjectId(projectId); run.setTaskId(taskId);
        run.setProjectRepositoryId(repositoryId); run.setHeadCommit(head); run.setResolvedTargetCommit(target);
        run.setTargetBranch("main"); run.setStatus("FAILED");
        run.setReport(Map.of("mergeable", false, "conflicts", List.of("src/a.java"),
                "tests", Map.of("status", "SKIPPED", "reason", "MERGE_CONFLICT")));
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return run;
    }
}
