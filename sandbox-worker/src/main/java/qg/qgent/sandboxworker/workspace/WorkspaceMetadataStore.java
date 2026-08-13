package qg.qgent.sandboxworker.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只读解析 Workspace Manager 保存的受控仓库元数据。 */
@Component
@RequiredArgsConstructor
public class WorkspaceMetadataStore {
    private final SandboxWorkerProperties properties;
    private final ObjectMapper objectMapper;

    public Map<UUID, String> resolveRepositories(String storageKey, List<UUID> repositoryIds) {
        UUID workspaceId = workspaceId(storageKey);
        Path metadata = Path.of(properties.getWorkspaceMetadataRoot()).toAbsolutePath().normalize()
                .resolve(workspaceId + ".json").normalize();
        if (!Files.isRegularFile(metadata))
            throw new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "Workspace 不存在");
        try {
            WorkspaceResponse workspace = objectMapper.readValue(metadata.toFile(), WorkspaceResponse.class);
            if (!storageKey.equals(workspace.getStorageKey()))
                throw invalid("WORKSPACE_METADATA_INVALID", "Workspace 存储标识与元数据不一致");
            Map<UUID, String> selected = new LinkedHashMap<>();
            for (UUID repositoryId : repositoryIds) {
                WorkspaceRepositoryResponse repository = workspace.getRepositories().stream()
                        .filter(value -> value.getRepositoryId().equals(repositoryId)).findFirst()
                        .orElseThrow(() -> invalid("SANDBOX_REPOSITORY_NOT_REGISTERED", "请求的仓库未登记在 Workspace 中"));
                selected.put(repositoryId, repository.getWorkspacePath());
            }
            return Map.copyOf(selected);
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("WORKSPACE_METADATA_INVALID", "Workspace 元数据损坏或无法读取");
        }
    }

    private UUID workspaceId(String storageKey) {
        if (storageKey == null || !storageKey.matches("workspaces/[0-9a-fA-F-]{36}"))
            throw invalid("WORKSPACE_STORAGE_KEY_INVALID", "Workspace 存储标识不合法");
        try {
            return UUID.fromString(storageKey.substring("workspaces/".length()));
        } catch (IllegalArgumentException exception) {
            throw invalid("WORKSPACE_STORAGE_KEY_INVALID", "Workspace 存储标识不合法");
        }
    }

    private WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
