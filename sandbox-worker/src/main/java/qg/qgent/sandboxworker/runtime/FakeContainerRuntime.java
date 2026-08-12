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
        SandboxAllocation existing = allocations.putIfAbsent(request.getSandboxId(), allocation);
        if (existing != null) {
            throw new IllegalStateException("沙箱编号已经存在");
        }
        return allocation;
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
    public boolean isWorkspaceInUse(String workspaceStorageKey) {
        return allocations.values().stream()
                .anyMatch(item -> item.getWorkspaceStorageKey().equals(workspaceStorageKey));
    }

    @Override
    public void destroy(UUID sandboxId) {
        allocations.remove(sandboxId);
    }
}
