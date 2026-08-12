package qg.qgent.sandboxworker.runtime;

import qg.qgent.sandboxworker.api.CreateSandboxRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 沙箱容器生命周期抽象。
 * 开发环境可以使用内存实现，生产环境由 Docker 实现负责真实资源管理。
 */
public interface ContainerRuntime {

    /**
     * 创建一个新的 Sandbox。
     *
     * @param request 经过接口校验的创建请求
     * @param allocation 服务层计算完成的租约和资源元数据
     * @return 已受运行时管理的沙箱状态
     */
    SandboxAllocation create(CreateSandboxRequest request, SandboxAllocation allocation);

    /**
     * @param sandboxId 沙箱编号
     * @return 沙箱存在时返回运行时状态，否则返回空
     */
    Optional<SandboxAllocation> find(UUID sandboxId);

    /**
     * @return 当前运行时管理的全部沙箱快照
     */
    List<SandboxAllocation> findAll();

    /** 判断任一受管 Sandbox 是否仍在使用指定 Workspace。 */
    boolean isWorkspaceInUse(String workspaceStorageKey);

    /**
     * 销毁指定 Sandbox 的临时运行资源，不删除 Workspace。
     *
     * @param sandboxId 沙箱编号
     */
    void destroy(UUID sandboxId);
}
