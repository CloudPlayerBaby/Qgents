package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.List;
import java.util.UUID;

/**
 * 目标已满足判定：质量修复步骤中 Coding Agent 声明 success 但本次没有任何 changed=true 写入时，
 * 若本步骤声明的全部目标文件已存在于 Workspace 且**内容可读（非空文件）**，说明目标已被前序步骤
 * （越界完成）或历史提交满足，应判定为 SUCCEEDED 而不是 CODING_NO_ACTUAL_CHANGE。
 * <p>
 * 收紧语义：普通 MUTATE 步骤不使用本兜底（调用方 CodingAgent 仅在 qualityRepair 时调用）；
 * 本判定不只核对存在性——目标必须能被 {@link WorkspaceCodeAccess#readFile} 读到且内容非空，
 * 避免「文件存在但内容错误/空文件」被存在性掩盖而误判完成。目标声明为空、路径非法、读取失败
 * 或内容为空一律返回 false，保持原有严格模式（零变更必须真实失败）。
 */
public final class TargetSatisfaction {

    private TargetSatisfaction() {
    }

    /**
     * 检查本步骤声明的全部目标文件是否已存在且内容可读（非空）。
     *
     * @param codeAccess 工作区只读访问；{@code readFile} 读取内容。
     * @param workspaceId 目标 Workspace
     * @param targets 本步骤声明的目标文件（可能同时含裸路径与 worktree 前缀两种形态）
     * @return true=每个目标已存在且内容非空；false=目标为空、非法、读取失败或内容为空
     */
    public static boolean isSatisfied(WorkspaceCodeAccess codeAccess, UUID workspaceId, List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        try {
            for (String raw : targets) {
                String target = TaskStepPathPolicy.normalize(raw);
                if (target == null || target.isBlank()) {
                    // 非法目标路径不能 continue 后当成满足：缺失一个合法目标即整体不满足。
                    return false;
                }
                WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, target);
                if (read == null || !read.isOk()) {
                    return false;
                }
                String content = read.getContent();
                if (content == null || content.isEmpty()) {
                    // 空文件不算「目标已满足」——空文件可能是本次任务该写入内容却只创建了占位。
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            // 核对本身失败（Workspace 短暂不可用等）时不放行，回退到原有零变更失败判定。
            return false;
        }
    }
}
