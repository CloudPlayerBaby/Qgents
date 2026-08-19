package qg.qgent.orchestration.preview;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 目录是持久工作树事实，但没有可提交的 Git 文件 Diff，不能触发预览计算。
 */
class WorkspaceDiffPreviewWriteObserverTest {

    private final WorkspaceDiffPreviewService previewService = mock(WorkspaceDiffPreviewService.class);
    private final WorkspaceDiffPreviewWriteObserver observer = new WorkspaceDiffPreviewWriteObserver(previewService);
    private final UUID projectId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID taskRunId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void directoryCreationDoesNotTriggerDiffPreview() {
        observer.onWrite(projectId, taskId, taskRunId, workspaceId,
                WorkspaceDirectoryResult.ok("src/generated", true));

        verify(previewService, never()).record(projectId, taskId, taskRunId, workspaceId);
    }

    @Test
    void changedFileStillTriggersDiffPreview() {
        observer.onWrite(projectId, taskId, taskRunId, workspaceId,
                WorkspaceWriteResult.ok("src/generated/App.java", "hash", true));

        verify(previewService).record(projectId, taskId, taskRunId, workspaceId);
    }

    @Test
    void unchangedFileWriteDoesNotTriggerDiffPreview() {
        observer.onWrite(projectId, taskId, taskRunId, workspaceId,
                WorkspaceWriteResult.ok("src/generated/App.java", "hash", false));

        verify(previewService, never()).record(projectId, taskId, taskRunId, workspaceId);
    }
}
