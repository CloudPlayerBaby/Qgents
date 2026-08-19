package qg.qgent.orchestration.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.agent.CodingWriteObserver;
import qg.qgent.orchestration.tool.WorkspaceChangeResult;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;

import java.util.UUID;

/**
 * 把成功写工具触发的实时 Diff Preview 记录接入 Coding 主循环（阶段 D）。
 * 注册为 Spring bean，经 {@code CodingAgent.setWriteObserver} 注入（可空，测试默认无）。
 * <p>
 * 只处理 ok 写；任何异常都被吞掉并记日志，绝不中断 Coding 主循环。
 * 每次成功写后重新计算累积工作树 diff（service 内部按 workingTreeHash 幂等跳过），
 * 所以高频 patch 只产生工作树真正变化时的 revision，事件只发元数据。
 */
@Slf4j
@Component
public class WorkspaceDiffPreviewWriteObserver implements CodingWriteObserver {

    private final WorkspaceDiffPreviewService previewService;

    public WorkspaceDiffPreviewWriteObserver(WorkspaceDiffPreviewService previewService) {
        this.previewService = previewService;
    }

    @Override
    public void onWrite(UUID projectId, UUID taskId, UUID taskRunId, UUID workspaceId,
                        WorkspaceChangeResult result) {
        if (result == null || !result.isOk()) {
            return;
        }
        if (result instanceof WorkspaceDirectoryResult) {
            return;
        }
        try {
            previewService.record(projectId, taskId, taskRunId, workspaceId);
        } catch (RuntimeException e) {
            // 预览失败绝不阻塞 Coding 主循环（与 AGENTS.md 失败吞异常语义一致）。
            log.warn("WORKSPACE_DIFF_PREVIEW_RECORD_FAILED workspaceId={} {}: {}",
                    workspaceId, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
