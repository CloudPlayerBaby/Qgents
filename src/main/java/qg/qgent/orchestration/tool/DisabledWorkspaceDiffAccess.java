package qg.qgent.orchestration.tool;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Git 服务/Sandbox 未接入时的占位实现：任何 diff 请求都返回明确"未就绪"结果，
 * 绝不落到宿主机执行 git 命令，也不伪造 base/head commit，保证最小权限。
 */
@Component
public class DisabledWorkspaceDiffAccess implements WorkspaceDiffAccess {

    @Override
    public GitDiffResult diff(UUID workspaceId) {
        return GitDiffResult.unavailable();
    }
}
