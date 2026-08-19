package qg.qgent.orchestration.preview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.WorkspaceDiffPreviewFileResponse;
import qg.qgent.dto.WorkspaceDiffPreviewResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceDiffPreviewEntity;
import qg.qgent.entity.WorkspaceDiffPreviewRevisionEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewMapper;
import qg.qgent.mapper.WorkspaceDiffPreviewRevisionMapper;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.DiffSnapshotStorage;
import qg.qgent.service.EventService;
import qg.qgent.service.GroupService;
import qg.qgent.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkspaceDiffPreviewService 测试（阶段 D）：成功写后把累积工作树 diff 落为单调递增 revision
 * 并发布元数据事件；同 workingTreeHash 幂等跳过；Worker 未启用 / diff 不可用 / 无 tree hash /
 * 快照失败时记日志跳过，绝不打点、绝不发布（不产生半条记录）。
 */
class WorkspaceDiffPreviewServiceTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID TASK_RUN_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String PATCH = "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n+new line\n";
    private static final String TREE_HASH =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SNAPSHOT_KEY = "diff-snapshots/00000000-0000-0000-0000-000000000000.patch";

    private final WorkspaceDiffAccess diffAccess = mock(WorkspaceDiffAccess.class);
    private final EventService eventService = mock(EventService.class);
    private final DiffSnapshotStorage snapshots = mock(DiffSnapshotStorage.class);
    private final WorkspaceDiffPreviewMapper previewMapper = mock(WorkspaceDiffPreviewMapper.class);
    private final WorkspaceDiffPreviewRevisionMapper revisionMapper = mock(WorkspaceDiffPreviewRevisionMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groups = mock(GroupService.class);
    private final TaskMapper tasks = mock(TaskMapper.class);

    /**
     * 纯单元测试未启动 MyBatis/Spring，{@code getSqlSegment()} 解析 lambda 列名需要实体 TableInfo；
     * 显式注册涉及实体，避免裸 JVM 下懒初始化列缓存的行为差异导致测试偶发失败。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, WorkspaceDiffPreviewRevisionEntity.class);
    }

    private WorkspaceDiffPreviewService enabled() {
        return new WorkspaceDiffPreviewService(diffAccess, eventService, snapshots, previewMapper, revisionMapper,
                access, groups, tasks, true);
    }

    private WorkspaceDiffPreviewService disabled() {
        return new WorkspaceDiffPreviewService(diffAccess, eventService, snapshots, previewMapper, revisionMapper,
                access, groups, tasks, false);
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(TASK_ID);
        task.setProjectId(PROJECT_ID);
        task.setWorkspaceId(WORKSPACE_ID);
        task.setRequirementGroupId(GROUP_ID);
        return task;
    }

    private WorkspaceDiffPreviewRevisionEntity revision(long n, String snapshotKey) {
        WorkspaceDiffPreviewRevisionEntity rev = new WorkspaceDiffPreviewRevisionEntity();
        rev.setId(UUID.randomUUID());
        rev.setProjectId(PROJECT_ID);
        rev.setTaskId(TASK_ID);
        rev.setTaskRunId(TASK_RUN_ID);
        rev.setWorkspaceId(WORKSPACE_ID);
        rev.setRevision(n);
        rev.setBaseCommit("base");
        rev.setWorkingTreeHash(TREE_HASH + n);
        rev.setSnapshotKey(snapshotKey);
        rev.setFilesChanged(2);
        rev.setAdditions(5);
        rev.setDeletions(1);
        rev.setCreatedAt(LocalDateTime.of(2026, 8, 16, 10, 0).atOffset(ZoneOffset.UTC).toLocalDateTime());
        return rev;
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordsAndPublishesFirstRevision() {
        when(diffAccess.diff(WORKSPACE_ID))
                .thenReturn(GitDiffResult.ok(PATCH, "base", "head", TREE_HASH, 3, 10, 2));
        when(snapshots.store(any(UUID.class), eq(PATCH))).thenReturn(SNAPSHOT_KEY);

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        // 先存快照再落 revision 行，且事件在持久化成功后才发布。
        verify(snapshots).store(any(UUID.class), eq(PATCH));

        ArgumentCaptor<WorkspaceDiffPreviewRevisionEntity> revCaptor =
                ArgumentCaptor.forClass(WorkspaceDiffPreviewRevisionEntity.class);
        verify(revisionMapper).insert(revCaptor.capture());
        WorkspaceDiffPreviewRevisionEntity rev = revCaptor.getValue();
        assertThat(rev.getId()).isNotNull();
        assertThat(rev.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(rev.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(rev.getTaskId()).isEqualTo(TASK_ID);
        assertThat(rev.getTaskRunId()).isEqualTo(TASK_RUN_ID);
        assertThat(rev.getRevision()).isEqualTo(1L);
        assertThat(rev.getBaseCommit()).isEqualTo("base");
        assertThat(rev.getWorkingTreeHash()).isEqualTo(TREE_HASH);
        assertThat(rev.getSnapshotKey()).isEqualTo(SNAPSHOT_KEY);
        assertThat(rev.getFilesChanged()).isEqualTo(3);
        assertThat(rev.getAdditions()).isEqualTo(10);
        assertThat(rev.getDeletions()).isEqualTo(2);
        assertThat(rev.getCreatedAt()).isNotNull();

        ArgumentCaptor<WorkspaceDiffPreviewEntity> headerCaptor =
                ArgumentCaptor.forClass(WorkspaceDiffPreviewEntity.class);
        verify(previewMapper).insert(headerCaptor.capture());
        assertThat(headerCaptor.getValue().getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(headerCaptor.getValue().getLatestRevision()).isEqualTo(1L);

        // 事件只发元数据，绝不携带 patch/源码。
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).publish(eq(PROJECT_ID), isNull(), eq("workspace.diff-preview.updated"),
                eq(WORKSPACE_ID.toString()), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("eventVersion")).isEqualTo(1);
        assertThat(payload.get("projectId")).isEqualTo(PROJECT_ID);
        assertThat(payload.get("taskId")).isEqualTo(TASK_ID);
        assertThat(payload.get("taskRunId")).isEqualTo(TASK_RUN_ID);
        assertThat(payload.get("workspaceId")).isEqualTo(WORKSPACE_ID);
        assertThat(payload.get("previewRevision")).isEqualTo(1L);
        assertThat(payload.get("filesChanged")).isEqualTo(3);
        assertThat(payload.get("additions")).isEqualTo(10);
        assertThat(payload.get("deletions")).isEqualTo(2);
        assertThat(payload).doesNotContainKey("patch").doesNotContainKey("content").doesNotContainKey("source");
    }

    @Test
    void idempotentSkipSameWorkingTreeHash() {
        when(diffAccess.diff(WORKSPACE_ID))
                .thenReturn(GitDiffResult.ok(PATCH, "base", "head", TREE_HASH, 3, 10, 2));
        WorkspaceDiffPreviewRevisionEntity existing = new WorkspaceDiffPreviewRevisionEntity();
        existing.setId(UUID.randomUUID());
        when(revisionMapper.selectOne(any())).thenReturn(existing);

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        verify(snapshots, never()).store(any(), any());
        verify(revisionMapper, never()).insert(any(WorkspaceDiffPreviewRevisionEntity.class));
        verify(previewMapper, never()).insert(any(WorkspaceDiffPreviewEntity.class));
        verify(eventService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void workerDisabledIsNoOp() {
        disabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        verify(diffAccess, never()).diff(any());
        verify(eventService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void diffUnavailableSkipsWithoutPublishing() {
        when(diffAccess.diff(WORKSPACE_ID)).thenReturn(GitDiffResult.unavailable());

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        verify(snapshots, never()).store(any(), any());
        verify(revisionMapper, never()).insert(any(WorkspaceDiffPreviewRevisionEntity.class));
        verify(eventService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void missingWorkingTreeHashSkips() {
        when(diffAccess.diff(WORKSPACE_ID)).thenReturn(GitDiffResult.ok(PATCH, "base", "head"));

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        verify(snapshots, never()).store(any(), any());
        verify(revisionMapper, never()).insert(any(WorkspaceDiffPreviewRevisionEntity.class));
        verify(eventService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void snapshotFailureSkipsWithoutEventOrRow() {
        when(diffAccess.diff(WORKSPACE_ID))
                .thenReturn(GitDiffResult.ok(PATCH, "base", "head", TREE_HASH, 3, 10, 2));
        when(snapshots.store(any(UUID.class), any())).thenThrow(new IllegalStateException("disk full"));

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        // 快照失败则本次预览不落库、不发事件，不产生半条记录。
        verify(revisionMapper, never()).insert(any(WorkspaceDiffPreviewRevisionEntity.class));
        verify(previewMapper, never()).insert(any(WorkspaceDiffPreviewEntity.class));
        verify(eventService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void secondRecordIncrementsRevisionAndBumpsHeader() {
        when(diffAccess.diff(WORKSPACE_ID))
                .thenReturn(GitDiffResult.ok(PATCH, "base", "head", TREE_HASH + "1", 3, 10, 2),
                        GitDiffResult.ok(PATCH, "base", "head", TREE_HASH + "2", 4, 11, 3));
        when(snapshots.store(any(UUID.class), eq(PATCH))).thenReturn(SNAPSHOT_KEY);
        WorkspaceDiffPreviewEntity header = new WorkspaceDiffPreviewEntity();
        header.setId(UUID.randomUUID());
        header.setLatestRevision(1L);
        when(previewMapper.selectOne(any())).thenReturn(null).thenReturn(header);

        WorkspaceDiffPreviewService service = enabled();
        service.record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);
        service.record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        ArgumentCaptor<WorkspaceDiffPreviewRevisionEntity> revCaptor =
                ArgumentCaptor.forClass(WorkspaceDiffPreviewRevisionEntity.class);
        verify(revisionMapper, times(2)).insert(revCaptor.capture());
        List<WorkspaceDiffPreviewRevisionEntity> revisions = revCaptor.getAllValues();
        assertThat(revisions.get(0).getRevision()).isEqualTo(1L);
        assertThat(revisions.get(1).getRevision()).isEqualTo(2L);

        ArgumentCaptor<WorkspaceDiffPreviewEntity> headerCaptor =
                ArgumentCaptor.forClass(WorkspaceDiffPreviewEntity.class);
        verify(previewMapper).updateById(headerCaptor.capture());
        assertThat(headerCaptor.getValue().getLatestRevision()).isEqualTo(2L);

        verify(eventService, times(2)).publish(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishUsesRequirementGroupIdForSseScope() {
        when(diffAccess.diff(WORKSPACE_ID))
                .thenReturn(GitDiffResult.ok(PATCH, "base", "head", TREE_HASH, 3, 10, 2));
        when(snapshots.store(any(UUID.class), eq(PATCH))).thenReturn(SNAPSHOT_KEY);
        when(tasks.selectById(TASK_ID)).thenReturn(task());

        enabled().record(PROJECT_ID, TASK_ID, TASK_RUN_ID, WORKSPACE_ID);

        // SSE 广播范围使用 Task 所属需求群，遵循需求群隔离，而不是空 groupId 广播全项目。
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).publish(eq(PROJECT_ID), eq(GROUP_ID), eq("workspace.diff-preview.updated"),
                eq(WORKSPACE_ID.toString()), payloadCaptor.capture());
    }

    @Test
    void purgeExpiredDeletesOldRevisionsAndHeaders() {
        enabled().purgeExpired();

        verify(revisionMapper).delete(any());
        verify(previewMapper).delete(any());
    }

    // ---- 阶段 E：只读查询（preview / files） ----

    private static final String QUERY_PATCH = "===== repo =====\n"
            + "diff --git a/src/main/java/A.java b/src/main/java/A.java\n"
            + "index 111..222 100644\n"
            + "--- a/src/main/java/A.java\n"
            + "+++ b/src/main/java/A.java\n"
            + "@@ -1,2 +1,3 @@\n"
            + " context\n"
            + "-old line\n"
            + "+new line\n"
            + "+another\n"
            + "diff --git a/src/main/java/B.java b/src/main/java/B.java\n"
            + "new file mode 100644\n"
            + "--- /dev/null\n"
            + "+++ b/src/main/java/B.java\n"
            + "@@ -0,0 +1,2 @@\n"
            + "+line1\n"
            + "+line2\n";

    @Test
    void previewReturnsLatestRevisionWithPatch() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(revision(2, SNAPSHOT_KEY));
        when(snapshots.load(SNAPSHOT_KEY)).thenReturn(QUERY_PATCH);

        WorkspaceDiffPreviewResponse response = enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null);

        verify(access).requireProjectMember(PROJECT_ID, ACTOR);
        assertThat(response.getWorkspaceId()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(response.getTaskId()).isEqualTo(TASK_ID.toString());
        assertThat(response.getTaskRunId()).isEqualTo(TASK_RUN_ID.toString());
        assertThat(response.getRevision()).isEqualTo(2L);
        assertThat(response.getBaseCommit()).isEqualTo("base");
        assertThat(response.getWorkingTreeHash()).isEqualTo(TREE_HASH + "2");
        assertThat(response.getFilesChanged()).isEqualTo(2);
        assertThat(response.getAdditions()).isEqualTo(5);
        assertThat(response.getDeletions()).isEqualTo(1);
        assertThat(response.getPatch()).isEqualTo(QUERY_PATCH);
        assertThat(response.getCreatedAt()).isNotBlank();
    }

    @Test
    void previewByExplicitRevisionUsesThatRevision() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        WorkspaceDiffPreviewRevisionEntity rev = revision(7, SNAPSHOT_KEY);
        when(revisionMapper.selectOne(any())).thenReturn(rev);
        when(snapshots.load(SNAPSHOT_KEY)).thenReturn(QUERY_PATCH);

        WorkspaceDiffPreviewResponse response = enabled().preview(PROJECT_ID, TASK_ID, ACTOR, 7L);

        assertThat(response.getRevision()).isEqualTo(7L);
    }

    @Test
    void previewRejectsWhenTaskProjectMismatch() {
        TaskEntity otherTask = task();
        otherTask.setProjectId(UUID.randomUUID());
        when(tasks.selectById(TASK_ID)).thenReturn(otherTask);

        Throwable thrown = catchThrowable(() -> enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo("WORKSPACE_DIFF_PREVIEW_NOT_FOUND");
        verify(revisionMapper, never()).selectOne(any());
    }

    @Test
    void previewRejectsNonMember() {
        when(access.requireProjectMember(PROJECT_ID, ACTOR))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"));

        Throwable thrown = catchThrowable(() -> enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo("PROJECT_NOT_FOUND");
        verify(tasks, never()).selectById(any());
    }

    @Test
    void previewRejectsNonRequirementGroupMember() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED", "你不是该需求群成员"))
                .when(groups).requireGroupMember(PROJECT_ID, GROUP_ID, ACTOR);

        Throwable thrown = catchThrowable(() -> enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo("GROUP_MEMBER_REQUIRED");
        verify(revisionMapper, never()).selectOne(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revisionQueryIsScopedToProjectTaskAndWorkspace() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(revision(1, SNAPSHOT_KEY));
        when(snapshots.load(SNAPSHOT_KEY)).thenReturn(QUERY_PATCH);

        enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null);

        // 复用 Workspace 时不能读到其他 Task 的预览：查询必须同时约束 projectId + taskId + workspaceId。
        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(revisionMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("project_id =").contains("task_id =").contains("workspace_id =");
    }

    @Test
    void previewNotFoundWhenNoRevision() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(null);

        Throwable thrown = catchThrowable(() -> enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo("WORKSPACE_DIFF_PREVIEW_NOT_FOUND");
    }

    @Test
    void previewSnapshotMissingReturnsNullPatch() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(revision(1, SNAPSHOT_KEY));
        when(snapshots.load(SNAPSHOT_KEY)).thenReturn(null);

        WorkspaceDiffPreviewResponse response = enabled().preview(PROJECT_ID, TASK_ID, ACTOR, null);

        assertThat(response.getPatch()).isNull();
    }

    @Test
    void filesParsesPatchIntoStructuredEntries() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(revision(2, SNAPSHOT_KEY));
        when(snapshots.load(SNAPSHOT_KEY)).thenReturn(QUERY_PATCH);

        List<WorkspaceDiffPreviewFileResponse> files = enabled().files(PROJECT_ID, TASK_ID, ACTOR, null);

        assertThat(files).hasSize(2);
        WorkspaceDiffPreviewFileResponse modified = files.get(0);
        assertThat(modified.getPath()).isEqualTo("src/main/java/A.java");
        assertThat(modified.getChangeType()).isEqualTo("MODIFIED");
        assertThat(modified.getAdditions()).isEqualTo(2);
        assertThat(modified.getDeletions()).isEqualTo(1);
        assertThat(modified.getBinary()).isFalse();
        WorkspaceDiffPreviewFileResponse added = files.get(1);
        assertThat(added.getPath()).isEqualTo("src/main/java/B.java");
        assertThat(added.getChangeType()).isEqualTo("ADDED");
        assertThat(added.getAdditions()).isEqualTo(2);
        assertThat(added.getDeletions()).isZero();
    }

    @Test
    void filesEmptyWhenNoSnapshot() {
        when(tasks.selectById(TASK_ID)).thenReturn(task());
        when(revisionMapper.selectOne(any())).thenReturn(revision(1, null));

        List<WorkspaceDiffPreviewFileResponse> files = enabled().files(PROJECT_ID, TASK_ID, ACTOR, null);

        assertThat(files).isEmpty();
    }
}
