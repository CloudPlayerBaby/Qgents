package qg.qgent.sandboxworker.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /** 创建或幂等校验一个多仓库 Workspace。 */
    @PutMapping("/{workspaceId}")
    public WorkspaceResponse provision(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody WorkspaceProvisionRequest request) {
        return workspaceManagerService.provision(workspaceId, request);
    }

    /** 查询 Workspace 和各仓库当前 HEAD。 */
    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable UUID workspaceId) {
        return workspaceManagerService.get(workspaceId);
    }

    /** 幂等移除 Workspace worktree，不删除共享 Git Store。 */
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID workspaceId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        workspaceManagerService.delete(workspaceId);
    }
}
