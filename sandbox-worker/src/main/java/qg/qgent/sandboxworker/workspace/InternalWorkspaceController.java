package qg.qgent.sandboxworker.workspace;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 供 Orchestrator 调用的 Workspace Manager 内部接口。
 * 接口只接受资源编号和 Git 引用，不接受任意宿主机路径或远程仓库地址。
 */
@Validated
@RestController
@RequestMapping("/internal/v1/workspaces")
@RequiredArgsConstructor
public class InternalWorkspaceController {
    private final WorkspaceManagerService workspaceManagerService;

    /** 创建一个新的多仓库 Workspace。 */
    @PutMapping("/{workspaceId}")
    public WorkspaceResponse provision(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceProvisionRequest request) {
        return workspaceManagerService.provision(workspaceId, request);
    }

    /** 查询 Workspace 和各仓库当前 HEAD。 */
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable UUID workspaceId) {
        return workspaceManagerService.get(workspaceId);
    }

    /** 移除 Workspace 独立仓库，不删除共享 Git Store。 */
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId) {
        workspaceManagerService.delete(workspaceId);
    }

    /** 固化一个仓库当前未提交工作树，返回独立且可重复使用的测试 Workspace。 */
    @PostMapping("/{workspaceId}/repositories/{repositoryId}/test-snapshots/{snapshotWorkspaceId}")
    public WorkspaceResponse snapshotForTest(@PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId, @PathVariable UUID snapshotWorkspaceId,
            @RequestParam UUID projectId) {
        return workspaceManagerService.snapshotForTest(workspaceId, repositoryId, snapshotWorkspaceId, projectId);
    }

    /** 查询仓库当前分支、HEAD 和结构化变更。 */
    @GetMapping("/{workspaceId}/repositories/{repositoryId}/git/status")
    public GitStatusResponse gitStatus(@PathVariable UUID workspaceId, @PathVariable UUID repositoryId) {
        return workspaceManagerService.gitStatus(workspaceId, repositoryId);
    }

    /** 生成包含未跟踪文件的完整 Diff。 */
    @PostMapping("/{workspaceId}/repositories/{repositoryId}/git/diff")
    public GitDiffResponse gitDiff(@PathVariable UUID workspaceId, @PathVariable UUID repositoryId,
            @RequestBody(required = false) GitDiffRequest request) {
        return workspaceManagerService.gitDiff(workspaceId, repositoryId);
    }

    /** 校验审查快照并在内部执行 add -A 与 commit。 */
    @PostMapping("/{workspaceId}/repositories/{repositoryId}/git/commit")
    public GitCommitResponse gitCommit(@PathVariable UUID workspaceId, @PathVariable UUID repositoryId,
            @Valid @RequestBody GitCommitRequest request) {
        return workspaceManagerService.gitCommit(workspaceId, repositoryId, request);
    }

    /** 将受控 sourceBranch 推送到共享 store 已配置的 origin。 */
    @PostMapping("/{workspaceId}/repositories/{repositoryId}/git/push")
    public GitPushResponse gitPush(@PathVariable UUID workspaceId, @PathVariable UUID repositoryId,
            @Valid @RequestBody GitPushRequest request) {
        return workspaceManagerService.gitPush(workspaceId, repositoryId, request);
    }
}
