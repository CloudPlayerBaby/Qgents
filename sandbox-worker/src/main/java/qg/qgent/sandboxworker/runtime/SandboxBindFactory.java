package qg.qgent.sandboxworker.runtime;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 构造逐仓挂载，并用只读空文件覆盖 linked worktree 的 .git 指针。 */
@Component
@RequiredArgsConstructor
public class SandboxBindFactory {
    private final WorkspacePathResolver paths;

    public List<Bind> create(SandboxAllocation allocation) {
        List<Bind> binds = new ArrayList<>();
        for (var repositoryId : allocation.getRepositoryPaths().keySet()) {
            paths.resolveRepositoryLocal(allocation, repositoryId);
            String containerRepository = paths.resolveRepositoryContainer(allocation, repositoryId);
            binds.add(new Bind(paths.resolveRepositoryDockerHost(allocation, repositoryId).toString(),
                    new Volume(containerRepository), AccessMode.rw));
            binds.add(new Bind(paths.resolveGitMarkerDockerHost(allocation).toString(),
                    new Volume(containerRepository + "/.git"), AccessMode.ro));
        }
        return List.copyOf(binds);
    }
}
