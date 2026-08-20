package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DiffListItemResponse;
import qg.qgent.dto.FinalDiffPreviewResponse;
import qg.qgent.dto.DiffResponse;
import qg.qgent.dto.DiffCommentRequest;
import qg.qgent.dto.DiffCommentResponse;
import qg.qgent.entity.DiffCommentEntity;
import qg.qgent.entity.DiffFileEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Review-state tests for uncommitted Task Diff snapshots. */
class DiffServiceTest {
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final DiffDeliveryService delivery = mock(DiffDeliveryService.class);
    private final DiffFileMapper files = mock(DiffFileMapper.class);
    private final DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
    private final TaskRunMapper taskRuns = mock(TaskRunMapper.class);
    private final TaskStepMapper taskSteps = mock(TaskStepMapper.class);
    private final DiffCommentMapper comments = mock(DiffCommentMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final DiffService service = new DiffService(diffs, files, comments, batches, tasks,
            taskRuns, taskSteps, workspaces, access, events,
            mock(NotificationService.class), delivery, users);

    @Test
    void rejectRequiresReasonAndDoesNotCreateACommit() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId); TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(diff.getWorkspaceId()); task.setCreatedBy(actor);
        when(diffs.selectById(diff.getId())).thenReturn(diff); when(tasks.selectById(taskId)).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.decide(projectId, diff.getId(), actor, false, " "));

        assertEquals("DIFF_REJECT_REASON_REQUIRED", error.code());
        assertNull(diff.getHeadCommit()); verify(diffs, never()).updateById(diff);
    }

    @Test
    void acceptRecordsApprovalForTheExactSnapshot() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId); TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(diff.getWorkspaceId()); task.setCreatedBy(actor);
        when(diffs.selectById(diff.getId())).thenReturn(diff); when(tasks.selectById(taskId)).thenReturn(task);
        diff.setHeadCommit("base-head");
        DiffEntity committed = diff(projectId, taskId);
        committed.setId(diff.getId()); committed.setStatus("ACCEPTED"); committed.setReviewedBy(actor);
        committed.setHeadCommit("real-commit"); committed.setDeliveryStatus("COMMITTED");
        when(delivery.acceptNonBatch(task, diff, actor)).thenReturn(committed);

        DiffResponse response = service.decide(projectId, diff.getId(), actor, true, null);

        assertEquals("ACCEPTED", response.getStatus());
        assertEquals("real-commit", response.getHeadCommit());
        verify(delivery).acceptNonBatch(task, diff, actor);
    }

    @Test
    void listReturnsProjectDiffsWithRequirementGroupDerivedFromTask() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setRequirementGroupId(groupId);
        when(tasks.selectBatchIds(Set.of(taskId))).thenReturn(List.of(task));

        ApiPageResponse<DiffListItemResponse> page = service.list(projectId, taskId, actor, null, 20, "req");

        assertEquals(1, page.data().size());
        assertEquals(diff.getId().toString(), page.data().getFirst().getId());
        assertEquals(groupId.toString(), page.data().getFirst().getRequirementGroupId());
        assertEquals(diff.getSourceBranch(), page.data().getFirst().getSourceBranch());
        assertFalse(page.page().getHasMore());
    }

    @Test
    void createSeamPersistsDiffAndPublishesDiffCreated() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), repoId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setRequirementGroupId(groupId);
        when(tasks.selectById(taskId)).thenReturn(task);

        DiffResponse result = service.create(projectId, taskId, runId, stepId, repoId, workspaceId,
                "base", "feat/task-x", "tree-hash", Map.of("files", 2));

        assertEquals(runId.toString(), result.getTaskRunId());
        assertEquals(stepId.toString(), result.getTaskStepId());
        assertEquals(groupId.toString(), result.getRequirementGroupId());
        assertEquals("PENDING_REVIEW", result.getStatus());
        verify(diffs).insert(any(DiffEntity.class));
        verify(events).publish(eq(projectId), any(), eq("diff.created"), any(), any(Map.class));
    }

    @Test
    void finalPreviewReturnsSelectedFileAndCapsLinesAtOneHundred() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setWorkspaceId(workspaceId);
        diff.setReviewBatchId(UUID.randomUUID());
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(diff.getReviewBatchId()); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setWorkspaceId(workspaceId); batch.setFinalCodingTaskRunId(UUID.randomUUID());
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        stubFinalRun(projectId, diff, batch, task);
        DiffFileEntity file = previewFile(diff.getId(), 1L, "src/App.tsx", 201);
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(batches.selectById(diff.getReviewBatchId())).thenReturn(batch);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(files.selectCount(any())).thenReturn(1L);
        when(files.selectPreviewFileSummaries(diff.getId(), 101)).thenReturn(List.of(file));
        when(files.selectById(file.getId())).thenReturn(file);

        FinalDiffPreviewResponse response = service.finalPreview(projectId, diff.getId(), null, UUID.randomUUID());

        assertEquals("/app/projects/" + projectId + "/code/diff/" + diff.getId(), response.getDetailPath());
        assertEquals(200, response.getPreviewLineLimit());
        assertEquals(file.getId().toString(), response.getSelectedFileId());
        assertEquals("App.tsx", response.getFiles().getFirst().getFileName());
        assertEquals("tsx", response.getFiles().getFirst().getExtension());
        assertEquals(201, response.getTotalLineCount());
        assertEquals(200, response.getLines().size());
        assertTrue(response.getTruncated());
        assertTrue(response.getViewDetailsRequired());
        assertEquals("DELETE", response.getLines().getFirst().getType());
    }

    @Test
    void finalPreviewRejectsDiffOutsideFinalReviewBatch() {
        UUID projectId = UUID.randomUUID();
        DiffEntity intermediate = diff(projectId, UUID.randomUUID());
        when(diffs.selectById(intermediate.getId())).thenReturn(intermediate);

        ApiException error = assertThrows(ApiException.class,
                () -> service.finalPreview(projectId, intermediate.getId(), null, UUID.randomUUID()));

        assertEquals("DIFF_PREVIEW_FINAL_ONLY", error.code());
        verifyNoInteractions(files, batches);
    }

    @Test
    void finalPreviewAllowsSwitchingToAnotherFileOfSameDiff() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(UUID.randomUUID());
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setProjectId(projectId); batch.setTaskId(taskId); batch.setWorkspaceId(workspaceId);
        batch.setFinalCodingTaskRunId(UUID.randomUUID());
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        stubFinalRun(projectId, diff, batch, task);
        DiffFileEntity first = previewFile(diff.getId(), 1L, "a.txt", 1);
        DiffFileEntity selected = previewFile(diff.getId(), 2L, "nested/Login.java", 1);
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(batches.selectById(diff.getReviewBatchId())).thenReturn(batch);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(files.selectCount(any())).thenReturn(2L);
        when(files.selectPreviewFileSummaries(diff.getId(), 101)).thenReturn(List.of(first, selected));
        when(files.selectById(selected.getId())).thenReturn(selected);

        FinalDiffPreviewResponse response = service.finalPreview(projectId, diff.getId(), selected.getId(), UUID.randomUUID());

        assertEquals(selected.getId().toString(), response.getSelectedFileId());
        assertEquals("Login.java", response.getFiles().get(1).getFileName());
        assertEquals("java", response.getFiles().get(1).getExtension());
        assertEquals("delete 0", response.getLines().getFirst().getContent());
    }

    @Test
    void finalPreviewCapsFileTabsAndRejectsFilesBeyondTheCardLimit() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(UUID.randomUUID());
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setProjectId(projectId); batch.setTaskId(taskId); batch.setWorkspaceId(workspaceId);
        batch.setFinalCodingTaskRunId(UUID.randomUUID());
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        stubFinalRun(projectId, diff, batch, task);
        java.util.List<DiffFileEntity> fileRows = new java.util.ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            fileRows.add(previewFile(diff.getId(), index + 1L, "src/File" + index + ".java", 1));
        }
        DiffFileEntity hidden = fileRows.getLast();
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(batches.selectById(diff.getReviewBatchId())).thenReturn(batch);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(files.selectCount(any())).thenReturn(101L);
        when(files.selectPreviewFileSummaries(diff.getId(), 101)).thenReturn(fileRows);
        when(files.selectById(fileRows.getFirst().getId())).thenReturn(fileRows.getFirst());
        when(files.selectById(hidden.getId())).thenReturn(hidden);

        FinalDiffPreviewResponse response = service.finalPreview(projectId, diff.getId(), null, UUID.randomUUID());

        assertEquals(100, response.getFiles().size());
        assertTrue(response.getFilesTruncated());
        assertTrue(response.getViewDetailsRequired());
        ApiException error = assertThrows(ApiException.class,
                () -> service.finalPreview(projectId, diff.getId(), hidden.getId(), UUID.randomUUID()));
        assertEquals("DIFF_PREVIEW_FILE_LIMIT", error.code());
    }

    @Test
    void finalPreviewRejectsDiffThatWasNotProducedByTheFinalCodingRun() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(UUID.randomUUID());
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setProjectId(projectId); batch.setTaskId(taskId); batch.setWorkspaceId(workspaceId);
        batch.setFinalCodingTaskRunId(UUID.randomUUID());
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        stubFinalRun(projectId, diff, batch, task);
        diff.setTaskRunId(UUID.randomUUID());
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(batches.selectById(diff.getReviewBatchId())).thenReturn(batch);
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.finalPreview(projectId, diff.getId(), null, UUID.randomUUID()));

        assertEquals("DIFF_PREVIEW_CONTEXT_INVALID", error.code());
        verifyNoInteractions(files);
    }

    @Test
    void finalPreviewTruncatesAnOversizedSingleLine() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(UUID.randomUUID());
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setProjectId(projectId); batch.setTaskId(taskId); batch.setWorkspaceId(workspaceId);
        batch.setFinalCodingTaskRunId(UUID.randomUUID());
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        stubFinalRun(projectId, diff, batch, task);
        DiffFileEntity file = previewFile(diff.getId(), 1L, "long.txt", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) ((Map<?, ?>) file.getHunks().getFirst())
                .get("lines")).getFirst();
        row.put("content", "x".repeat(4_001));
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(batches.selectById(diff.getReviewBatchId())).thenReturn(batch);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(files.selectCount(any())).thenReturn(1L);
        when(files.selectPreviewFileSummaries(diff.getId(), 101)).thenReturn(List.of(file));
        when(files.selectById(file.getId())).thenReturn(file);

        FinalDiffPreviewResponse response = service.finalPreview(projectId, diff.getId(), null, UUID.randomUUID());

        assertEquals(4_000, response.getLines().getFirst().getContent().codePointCount(0,
                response.getLines().getFirst().getContent().length()));
        assertTrue(response.getLines().getFirst().getContentTruncated());
        assertTrue(response.getViewDetailsRequired());
    }

    @Test
    void commentsCarryAuthorNameAndAvatarUrl() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID(), diffId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setId(diffId);
        DiffCommentEntity comment = new DiffCommentEntity();
        comment.setId(UUID.randomUUID());
        comment.setDiffId(diffId);
        comment.setBody("密码有做哈希吗？");
        comment.setAuthorUserId(authorId);
        comment.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        when(diffs.selectById(diffId)).thenReturn(diff);
        when(comments.selectList(any())).thenReturn(List.of(comment));
        UserEntity author = new UserEntity();
        author.setId(authorId);
        author.setDisplayName("李同学");
        author.setAvatarUrl("https://cdn.example.com/avatars/user-002.png");
        when(users.selectBatchIds(Set.of(authorId))).thenReturn(List.of(author));

        List<DiffCommentResponse> result = service.comments(projectId, diffId, actor);

        assertEquals(1, result.size());
        DiffCommentResponse response = result.getFirst();
        assertEquals("李同学", response.getAuthorName());
        assertEquals("https://cdn.example.com/avatars/user-002.png", response.getAuthorAvatarUrl());
        assertEquals(authorId.toString(), response.getAuthorUserId());
    }

    @Test
    void addCommentReturnsAuthorAvatarFromFreshUserLookup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID diffId = UUID.randomUUID();
        DiffEntity diff = diff(projectId, taskId);
        diff.setId(diffId);
        when(diffs.selectById(diffId)).thenReturn(diff);
        UserEntity author = new UserEntity();
        author.setId(actor);
        author.setDisplayName("陈同学");
        author.setAvatarUrl("https://cdn.example.com/avatars/user-001.png");
        when(users.selectById(actor)).thenReturn(author);

        DiffCommentRequest request = new DiffCommentRequest();
        request.setBody("已使用 bcrypt");
        DiffCommentResponse response = service.addComment(projectId, diffId, actor, request);

        assertEquals("陈同学", response.getAuthorName());
        assertEquals("https://cdn.example.com/avatars/user-001.png", response.getAuthorAvatarUrl());
        assertEquals(actor.toString(), response.getAuthorUserId());
    }

    private DiffEntity diff(UUID projectId, UUID taskId) {
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID()); diff.setProjectId(projectId);
        diff.setTaskId(taskId); diff.setWorkspaceId(UUID.randomUUID()); diff.setProjectRepositoryId(UUID.randomUUID());
        diff.setStatus("PENDING_REVIEW"); diff.setWorkingTreeHash("tree-hash"); return diff;
    }

    private DiffFileEntity previewFile(UUID diffId, long sequence, String path, int lineCount) {
        DiffFileEntity file = new DiffFileEntity();
        file.setId(UUID.randomUUID()); file.setDiffId(diffId); file.setSequenceNo(sequence); file.setPath(path);
        file.setChangeType("MODIFIED"); file.setAdditions(lineCount); file.setDeletions(lineCount); file.setBinaryFlag(false);
        java.util.List<Object> lines = new java.util.ArrayList<>();
        for (int index = 0; index < lineCount; index++) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("type", index % 2 == 0 ? "DELETE" : "ADD");
            row.put("oldLineNo", index % 2 == 0 ? index + 1 : null);
            row.put("newLineNo", index % 2 == 0 ? null : index + 1);
            row.put("content", (index % 2 == 0 ? "delete " : "add ") + index);
            lines.add(row);
        }
        file.setHunks(List.of(Map.of("lines", lines)));
        return file;
    }

    private void stubFinalRun(UUID projectId, DiffEntity diff, DiffReviewBatchEntity batch, TaskEntity task) {
        UUID runId = batch.getFinalCodingTaskRunId();
        UUID stepId = UUID.randomUUID();
        diff.setTaskRunId(runId); diff.setTaskStepId(stepId);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId); run.setProjectId(projectId); run.setTaskId(task.getId()); run.setTaskStepId(stepId);
        run.setRole("DEVELOPER"); run.setStatus("SUCCEEDED");
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId); step.setTaskId(task.getId()); step.setRole("DEVELOPER");
        when(taskRuns.selectById(runId)).thenReturn(run);
        when(taskSteps.selectById(stepId)).thenReturn(step);
    }
}
