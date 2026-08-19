package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.util.List;
import java.util.UUID;

/**
 * 目标已满足判定：Coding Agent 声明 success 但本次没有任何 changed=true 写入时，
 * 若本步骤声明的全部目标文件已存在于 Workspace，说明目标已被前序步骤（越界完成）或
 * 历史提交满足，应判定为 SUCCEEDED 而不是 CODING_NO_ACTUAL_CHANGE，避免把幂等的
 * 跨步骤完成场景判成不可重试的语义失败。
 * <p>
 * 只核对存在性，不比较内容——前序步骤可能已写入且后续仍可能被本任务修订；目标路径已存在
 * 即视为本步骤职责已被覆盖。核对失败或目标声明为空时返回 false，保持原有严格模式
 * （零变更必须真实失败），避免因无法验证而错误放行。
 */
public final class TargetSatisfaction {

    private TargetSatisfaction() {
    }

    /**
     * 检查本步骤声明的全部目标文件是否已存在于 Workspace。
     *
     * @param codeAccess 工作区只读访问；{@code listFiles} 返回 Workspace 相对路径
     * @param workspaceId 目标 Workspace
     * @param targets 本步骤声明的目标文件（可能同时含裸路径与 worktree 前缀两种形态）
     * @return true=每个目标已存在（精确命中或作为目录前缀存在）；false=目标为空、核对失败或存在缺失目标
     */
    public static boolean isSatisfied(WorkspaceCodeAccess codeAccess, UUID workspaceId, List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        try {
            List<String> existing = codeAccess.listFiles(workspaceId);
            if (existing == null || existing.isEmpty()) {
                return false;
            }
            for (String raw : targets) {
                String target = TaskStepPathPolicy.normalize(raw);
                if (target == null) {
                    continue;
                }
                boolean present = existing.stream()
                        .anyMatch(file -> file.equals(target) || file.startsWith(target + "/"));
                if (!present) {
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
