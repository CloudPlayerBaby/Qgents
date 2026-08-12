package qg.qgent.sandboxworker.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 仅保存内存状态的安全开发实现，不执行任何宿主机命令。 */
@Component
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "fake", matchIfMissing = true)
public class FakeContainerRuntime implements ContainerRuntime {
    private final ConcurrentMap<UUID, SandboxAllocation> allocations = new ConcurrentHashMap<>();

    @Override
    public SandboxAllocation create(CreateSandboxRequest request, SandboxAllocation allocation) {
        return allocations.compute(request.getSandboxId(), (id, existing) -> {
            if (existing != null && (!existing.getTaskRunId().equals(request.getTaskRunId())
                    || !existing.getWorkspaceStorageKey().equals(request.getWorkspaceStorageKey())
                    || !existing.getImageProfile().equals(request.getImageProfile()))) {
                throw new IllegalStateException("该沙箱编号已分配给其他请求");
            }
            return existing != null ? existing : allocation;
        });
    }

    @Override
    public Optional<SandboxAllocation> find(UUID sandboxId) {
        return Optional.ofNullable(allocations.get(sandboxId));
    }

    @Override
    public List<SandboxAllocation> findAll() {
        return List.copyOf(allocations.values());
    }

    @Override
    public void destroy(UUID sandboxId) {
        allocations.remove(sandboxId);
    }
}
