package qg.qgent.sandboxworker.workspace;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
