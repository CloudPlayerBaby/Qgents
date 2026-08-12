package qg.qgent.sandboxworker.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.service.SandboxService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 将主后端中的 Workspace 描述落实为宿主机上的持久多仓库开发现场。
 * 元数据保存在独立受控目录中，不会挂载给 Agent，也不修改主后端业务数据库。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceManagerService {
    private final SandboxWorkerProperties properties;
    private final GitRepositoryManager repositories;
    private final SandboxService sandboxes;
    private final WorkspaceOperationLock workspaceLock;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 准备一个新的 Workspace；编号已经存在时返回冲突。
     * 创建任一仓库失败时回滚本次已创建的独立仓库和 Workspace 目录。
     */
    public synchronized WorkspaceResponse provision(UUID workspaceId, WorkspaceProvisionRequest request) {
        return workspaceLock.execute(storageKey(workspaceId), () -> provisionLocked(workspaceId, request));
    }

    private WorkspaceResponse provisionLocked(UUID workspaceId, WorkspaceProvisionRequest request) {
        validateRequest(request);
        Path metadata = metadataPath(workspaceId);
        if (Files.exists(metadata)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKSPACE_ALREADY_EXISTS", "Workspace 已经存在");
        }

        Path workspace = workspacePath(workspaceId);
        if (Files.exists(workspace)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKSPACE_PATH_EXISTS", "Workspace 目录存在但缺少受控元数据");
        }

        List<WorkspaceRepositoryResponse> created = new ArrayList<>();
        String now = clock.instant().toString();
        try {
            Files.createDirectories(workspace);
            for (WorkspaceRepositoryRequest repository : request.getRepositories()) {
                Path target = workspace.resolve(repository.getWorkspacePath()).normalize();
                String head = repositories.create(repository.getRepositoryId(), target,
                        repository.getBaseRef(), repository.getSourceBranch());
                created.add(new WorkspaceRepositoryResponse(repository.getRepositoryId(),
                        repository.getWorkspacePath(), repository.getSourceBranch(), repository.getBaseRef(), head));
            }
            WorkspaceResponse response = new WorkspaceResponse(workspaceId, request.getProjectId(),
                    storageKey(workspaceId), "READY", List.copyOf(created), now, now);
            write(metadata, response);
            return response;
        } catch (WorkerException exception) {
            rollback(workspace);
            throw exception;
        } catch (Exception exception) {
            rollback(workspace);
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_PROVISION_FAILED", "准备 Workspace 失败");
        }
    }

    /** 查询 Workspace 清单，并使用实际仓库 HEAD 刷新响应。 */
    public WorkspaceResponse get(UUID workspaceId) {
        Path metadata = metadataPath(workspaceId);
        if (!Files.isRegularFile(metadata)) {
            throw new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "Workspace 不存在");
        }
        return refresh(read(metadata));
    }

    /**
     * 删除 Workspace 下的独立仓库和元数据。
     * 共享 Git Store 不属于 Workspace 生命周期，永远不会在此处删除。
     */
    public synchronized void delete(UUID workspaceId) {
        workspaceLock.execute(storageKey(workspaceId), () -> {
            deleteLocked(workspaceId);
            return null;
        });
    }

    private void deleteLocked(UUID workspaceId) {
        Path metadata = metadataPath(workspaceId);
        if (!Files.exists(metadata)) {
            return;
        }
        WorkspaceResponse existing = read(metadata);
        if (sandboxes.isWorkspaceInUse(existing.getStorageKey())) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKSPACE_IN_USE", "Workspace 仍被运行中的 Sandbox 使用");
        }
        Path workspace = workspacePath(workspaceId);
        deleteTree(workspace);
        try {
            Files.deleteIfExists(metadata);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_METADATA_DELETE_FAILED", "Workspace 已删除，但元数据清理失败");
        }
    }

    private WorkspaceResponse refresh(WorkspaceResponse response) {
        Path workspace = workspacePath(response.getId());
        List<WorkspaceRepositoryResponse> repositoryResponses = response.getRepositories().stream()
                .map(repository -> new WorkspaceRepositoryResponse(
                        repository.getRepositoryId(),
                        repository.getWorkspacePath(),
                        repository.getSourceBranch(),
                        repository.getBaseRef(),
                        repositories.head(workspace.resolve(repository.getWorkspacePath()).normalize())))
                .toList();
        return new WorkspaceResponse(response.getId(), response.getProjectId(), response.getStorageKey(),
                "READY", repositoryResponses, response.getCreatedAt(), clock.instant().toString());
    }

    private void validateRequest(WorkspaceProvisionRequest request) {
        Set<UUID> repositoryIds = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (WorkspaceRepositoryRequest repository : request.getRepositories()) {
            if (!repositoryIds.add(repository.getRepositoryId())) {
                throw invalid("WORKSPACE_REPOSITORY_DUPLICATE", "同一 Workspace 不能重复声明仓库");
            }
            if (!paths.add(repository.getWorkspacePath())) {
                throw invalid("WORKSPACE_PATH_DUPLICATE", "多个仓库不能使用相同 Workspace 目录");
            }
        }
    }

    private WorkspaceResponse read(Path metadata) {
        try {
            return objectMapper.readValue(metadata.toFile(), WorkspaceResponse.class);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKSPACE_METADATA_INVALID", "Workspace 元数据损坏或无法读取");
        }
    }

    private void write(Path metadata, WorkspaceResponse response) {
        try {
            Files.createDirectories(metadata.getParent());
            Path temporary = Files.createTempFile(metadata.getParent(), ".workspace-", ".tmp");
            try {
                objectMapper.writeValue(temporary.toFile(), response);
                Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_METADATA_WRITE_FAILED", "无法保存 Workspace 元数据");
        }
    }

    private void rollback(Path workspace) {
        deleteTree(workspace);
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        Path expectedRoot = Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize();
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(expectedRoot) || normalized.equals(expectedRoot)) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "WORKSPACE_PATH_INVALID", "拒绝删除 Workspace 根目录之外的路径");
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_DELETE_FAILED", "无法完整删除 Workspace 目录");
        }
    }

    private Path workspacePath(UUID workspaceId) {
        return Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize()
                .resolve(workspaceId.toString()).normalize();
    }

    private Path metadataPath(UUID workspaceId) {
        return Path.of(properties.getWorkspaceMetadataRoot()).toAbsolutePath().normalize()
                .resolve(workspaceId + ".json").normalize();
    }

    private String storageKey(UUID workspaceId) {
        return "workspaces/" + workspaceId;
    }

    private WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

}
