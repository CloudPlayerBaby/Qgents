package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.UUID;

/**
 * Coding Agent 每次成功写工具后的回调（阶段 D：Workspace Diff Preview 触发点）。
 * <p>
 * 约定：
 * <ul>
 *   <li>仅成功写入（apply_patch / write_file 返回 ok=true）时触发，工具级失败不触发；</li>
 *   <li>实现必须快速失败并自行吞异常：预览记录失败不得阻塞或破坏 Coding 主循环；</li>
 *   <li>同一次 run 内按 workspaceId 共享，跨轮次回调累积（每次成功写各触发一次）。</li>
 * </ul>
 * 未注入（为 null）时 Coding 工具静默跳过，不影响编码流程。
 */
@FunctionalInterface
public interface CodingWriteObserver {

    /**
     * @param projectId  当前任务所属项目 ID。
     * @param taskId     当前任务 ID。
     * @param taskRunId  当前 TaskRun ID（Coding 相位必非空）。
     * @param workspaceId 被写入的工作区 ID。
     * @param result     成功写入结果（含 path/newSha256/changed）。
     */
    void onWrite(UUID projectId, UUID taskId, UUID taskRunId, UUID workspaceId, WorkspaceWriteResult result);
}
