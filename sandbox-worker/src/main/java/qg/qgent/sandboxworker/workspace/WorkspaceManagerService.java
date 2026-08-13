package qg.qgent.sandboxworker.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.service.SandboxService;
import qg.qgent.sandboxworker.runtime.WorkspacePathResolver;

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

/** 管理 Project 内持久 Workspace、仓库 worktree 和受控 Git 操作。 */
@Service
@RequiredArgsConstructor
public class WorkspaceManagerService {
    private final SandboxWorkerProperties properties;
    private final GitRepositoryManager repositories;
    private final SandboxService sandboxes;
    private final WorkspaceOperationLock workspaceLock;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 幂等准备 Workspace；相同编号但规格不同时拒绝。 */
    public WorkspaceResponse provision(UUID workspaceId, WorkspaceProvisionRequest request) {
        return workspaceLock.execute(storageKey(workspaceId), () -> provisionLocked(workspaceId, request));
    }

    private WorkspaceResponse provisionLocked(UUID workspaceId, WorkspaceProvisionRequest request) {
        validateRequest(request);
        Path metadata = metadataPath(workspaceId);
        if (Files.exists(metadata)) {
            WorkspaceResponse existing = read(metadata);
            if (sameSpec(existing, request)) {
                ensureGitMarker(workspacePath(workspaceId));
                return refresh(existing);
            }
            throw conflict("WORKSPACE_SPEC_CONFLICT", "Workspace 已存在但创建规格不同");
        }
        Path workspace = workspacePath(workspaceId);
        if (Files.exists(workspace))
            throw conflict("WORKSPACE_PATH_EXISTS", "Workspace 目录存在但缺少受控元数据");

        List<WorkspaceRepositoryResponse> created = new ArrayList<>();
        String now = clock.instant().toString();
        try {
            Files.createDirectories(workspace);
            ensureGitMarker(workspace);
            for (WorkspaceRepositoryRequest repository : request.getRepositories()) {
                Path target = workspace.resolve(repository.getWorkspacePath()).normalize();
                GitRepositoryManager.WorktreeResult result = repositories.create(repository.getRepositoryId(), target,
                        repository.getBaseRef(), repository.getSourceBranch());
                created.add(new WorkspaceRepositoryResponse(repository.getRepositoryId(), repository.getWorkspacePath(),
                        repository.getSourceBranch(), repository.getBaseRef(), result.baseCommit(),
                        result.headCommit()));
            }
            WorkspaceResponse response = new WorkspaceResponse(workspaceId, request.getProjectId(),
                    storageKey(workspaceId),
                    "READY", List.copyOf(created), now, now);
            write(metadata, response);
            return response;
        } catch (RuntimeException exception) {
            for (WorkspaceRepositoryResponse repository : created) {
                repositories.remove(repository.getRepositoryId(),
                        workspace.resolve(repository.getWorkspacePath()).normalize());
            }
            deleteTree(workspace);
            throw exception;
        } catch (Exception exception) {
            deleteTree(workspace);
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_PROVISION_FAILED",
                    "准备 Workspace 失败");
        }
    }

    /** 查询 Workspace，并刷新每个仓库的真实 HEAD。 */
    public WorkspaceResponse get(UUID workspaceId) {
        Path metadata = metadataPath(workspaceId);
        if (!Files.isRegularFile(metadata))
            throw new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "Workspace 不存在");
        return refresh(read(metadata));
    }

    /** 注销 linked worktree 并删除 Workspace，不删除共享 bare store。 */
    public void delete(UUID workspaceId) {
        workspaceLock.execute(storageKey(workspaceId), () -> {
            Path metadata = metadataPath(workspaceId);
            if (!Files.exists(metadata))
                return null;
            WorkspaceResponse existing = read(metadata);
            if (sandboxes.isWorkspaceInUse(existing.getStorageKey()))
                throw conflict("WORKSPACE_IN_USE", "Workspace 仍被运行中的 Sandbox 使用");
            Path workspace = workspacePath(workspaceId);
            for (WorkspaceRepositoryResponse repository : existing.getRepositories()) {
                repositories.remove(repository.getRepositoryId(),
                        workspace.resolve(repository.getWorkspacePath()).normalize());
            }
            deleteTree(workspace);
            try {
                Files.deleteIfExists(metadata);
            } catch (Exception exception) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "WORKSPACE_METADATA_DELETE_FAILED", "Workspace 已删除，但元数据清理失败");
            }
            return null;
        });
    }

    public GitStatusResponse gitStatus(UUID workspaceId, UUID repositoryId) {
        return workspaceLock.execute(storageKey(workspaceId), () -> {
            WorkspaceRepositoryResponse repository = requireRepository(get(workspaceId), repositoryId);
            return repositories.status(repositoryPath(workspaceId, repository));
        });
    }

    public GitDiffResponse gitDiff(UUID workspaceId, UUID repositoryId) {
        return workspaceLock.execute(storageKey(workspaceId), () -> {
            WorkspaceRepositoryResponse repository = requireRepository(get(workspaceId), repositoryId);
            return repositories.diff(repositoryPath(workspaceId, repository));
        });
    }

    public GitCommitResponse gitCommit(UUID workspaceId, UUID repositoryId, GitCommitRequest request) {
        return workspaceLock.execute(storageKey(workspaceId), () -> {
            WorkspaceResponse workspace = get(workspaceId);
            if (sandboxes.isWorkspaceInUse(workspace.getStorageKey()))
                throw conflict("WORKSPACE_IN_USE", "Workspace 仍被 Sandbox 使用，不能创建 Commit");
            WorkspaceRepositoryResponse repository = requireRepository(workspace, repositoryId);
            return repositories.commit(repositoryPath(workspaceId, repository), request);
        });
    }

    public GitPushResponse gitPush(UUID workspaceId, UUID repositoryId, GitPushRequest request) {
        return workspaceLock.execute(storageKey(workspaceId), () -> {
            WorkspaceRepositoryResponse repository = requireRepository(get(workspaceId), repositoryId);
            return repositories.push(repositoryId, repositoryPath(workspaceId, repository),
                    repository.getSourceBranch(), request);
        });
    }

    private WorkspaceResponse refresh(WorkspaceResponse response) {
        List<WorkspaceRepositoryResponse> refreshed = response.getRepositories().stream()
                .map(repository -> new WorkspaceRepositoryResponse(repository.getRepositoryId(),
                        repository.getWorkspacePath(),
                        repository.getSourceBranch(), repository.getBaseRef(), repository.getBaseCommit(),
                        repositories.head(repositoryPath(response.getId(), repository))))
                .toList();
        return new WorkspaceResponse(response.getId(), response.getProjectId(), response.getStorageKey(), "READY",
                refreshed, response.getCreatedAt(), clock.instant().toString());
    }

    private void validateRequest(WorkspaceProvisionRequest request) {
        Set<UUID> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (WorkspaceRepositoryRequest repository : request.getRepositories()) {
            if (!ids.add(repository.getRepositoryId()))
                throw invalid("WORKSPACE_REPOSITORY_DUPLICATE", "同一 Workspace 不能重复声明仓库");
            if (!paths.add(repository.getWorkspacePath()))
                throw invalid("WORKSPACE_PATH_DUPLICATE", "多个仓库不能使用相同 Workspace 目录");
        }
    }

    private boolean sameSpec(WorkspaceResponse existing, WorkspaceProvisionRequest request) {
        if (!existing.getProjectId().equals(request.getProjectId())
                || existing.getRepositories().size() != request.getRepositories().size())
            return false;
        return request.getRepositories().stream()
                .allMatch(candidate -> existing.getRepositories().stream()
                        .anyMatch(current -> current.getRepositoryId().equals(candidate.getRepositoryId())
                                && current.getWorkspacePath().equals(candidate.getWorkspacePath())
                                && current.getBaseRef().equals(candidate.getBaseRef())
                                && current.getSourceBranch().equals(candidate.getSourceBranch())));
    }

    private WorkspaceRepositoryResponse requireRepository(WorkspaceResponse workspace, UUID repositoryId) {
        return workspace.getRepositories().stream().filter(value -> value.getRepositoryId().equals(repositoryId))
                .findFirst()
                .orElseThrow(() -> new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_REPOSITORY_NOT_FOUND",
                        "Workspace 中不存在该仓库"));
    }

    private Path repositoryPath(UUID workspaceId, WorkspaceRepositoryResponse repository) {
        return workspacePath(workspaceId).resolve(repository.getWorkspacePath()).normalize();
    }

    private WorkspaceResponse read(Path metadata) {
        try {
            return objectMapper.readValue(metadata.toFile(), WorkspaceResponse.class);
        } catch (Exception exception) {
            throw conflict("WORKSPACE_METADATA_INVALID", "Workspace 元数据损坏或无法读取");
        }
    }

    /** 为升级前已存在的 Workspace 安全补建空 marker，并拒绝覆盖异常文件。 */
    private void ensureGitMarker(Path workspace) {
        Path marker = workspace.resolve(WorkspacePathResolver.GIT_MARKER).normalize();
        if (!marker.startsWith(workspace) || marker.equals(workspace)) {
            throw conflict("WORKSPACE_GIT_MARKER_INVALID", "Workspace Git 隔离 marker 路径越界");
        }
        try {
            if (Files.exists(marker)) {
                if (!Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 0) {
                    throw conflict("WORKSPACE_GIT_MARKER_INVALID", "Workspace Git 隔离 marker 不是受控空文件");
                }
                return;
            }
            Files.write(marker, new byte[0], java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_GIT_MARKER_CREATE_FAILED",
                    "无法创建 Workspace Git 隔离 marker");
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

    private void deleteTree(Path root) {
        if (!Files.exists(root))
            return;
        Path expectedRoot = Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize();
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.startsWith(expectedRoot) || normalized.equals(expectedRoot))
            throw conflict("WORKSPACE_PATH_INVALID", "拒绝删除 Workspace 根目录之外的路径");
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_DELETE_FAILED",
                    "无法完整删除 Workspace 目录");
        }
    }

    private Path workspacePath(UUID id) {
        return Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize().resolve(id.toString())
                .normalize();
    }

    private Path metadataPath(UUID id) {
        return Path.of(properties.getWorkspaceMetadataRoot()).toAbsolutePath().normalize().resolve(id + ".json")
                .normalize();
    }

    private String storageKey(UUID id) {
        return "workspaces/" + id;
    }

    private WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private WorkerException conflict(String code, String message) {
        return new WorkerException(HttpStatus.CONFLICT, code, message);
    }
}
