package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * {@link WorkspaceCodeWriter} 的 Worker 实现：通过 Worker 的 {@code file.write} 新建或整文件
 * 替换、{@code file.patch} 对已有文件精确应用统一 Diff。
 * <p>
 * Worker 的 file.write / file.patch 均采用"旧内容哈希校验 + 原子替换"的乐观并发控制：file.write
 * 先读取目标文件当前 sha256（不存在则取空内容哈希）再写入；file.patch 原样透传调用方提交的
 * expectedHash 与 patch。Worker 返回的哈希冲突、补丁上下文不匹配、路径非法等工具级失败映射为
 * 工具级失败回灌 LLM 纠正；传输/会话/轮询级失败映射为基础设施失败。
 * <p>
 * {@code app.worker.enabled=true} 时启用本实现。
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerWorkspaceCodeWriter extends AbstractWorkerToolPort implements WorkspaceCodeWriter {

    /**
     * 单次写入内容的最大字节数，与本地实现一致。
     */
    private static final int MAX_WRITE_BYTES = 256 * 1024;
    /**
     * 单次补丁文本的最大字节数，与 Worker file.patch 契约一致。
     */
    private static final int MAX_PATCH_BYTES = 1024 * 1024;
    /**
     * 空内容的 SHA-256，用于新建文件的 expectedHash。
     */
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    public WorkerWorkspaceCodeWriter(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Override
    public WorkspaceDirectoryResult createDirectory(UUID workspaceId, String path) {
        if (path == null || path.isBlank()) {
            return WorkspaceDirectoryResult.fail(path, "path must not be blank");
        }
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceDirectoryResult.fail(path, "path does not map to a workspace repository");
        }
        try {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "directory.create",
                    Map.of("path", target.relativePath()), TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                Object created = resultOf(execution).get("created");
                return WorkspaceDirectoryResult.ok(path, Boolean.TRUE.equals(created));
            }
            return directoryFailure(path, execution);
        } catch (RuntimeException e) {
            return WorkspaceDirectoryResult.infraFail(path, "directory creation failed: " + e.getMessage());
        }
    }

    @Override
    public WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (content == null) {
            return WorkspaceWriteResult.fail(path, "content must not be null");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
            return WorkspaceWriteResult.fail(path, "content exceeds 256KB limit");
        }
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path does not map to a workspace repository");
        }
        try {
            WorkspaceDirectoryResult parent = ensureParentDirectory(workspaceId, target);
            if (!parent.isOk()) {
                return parent.isInfrastructureFailure()
                        ? WorkspaceWriteResult.infraFail(path, parent.getError())
                        : WorkspaceWriteResult.fail(path, parent.getError());
            }
            String expectedHash = currentHash(workspaceId, target);
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.write",
                    Map.of("path", target.relativePath(), "expectedHash", expectedHash, "content", content),
                    TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return okResult(path, execution, expectedHash);
            }
            return writeFailure(path, execution, "write failed");
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "write failed: " + e.getMessage());
        }
    }

    @Override
    public WorkspaceWriteResult patchFile(UUID workspaceId, String path, String expectedHash, String patch) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            return WorkspaceWriteResult.fail(path, "expectedHash must be 64 hex chars");
        }
        if (patch == null || patch.isBlank()) {
            return WorkspaceWriteResult.fail(path, "patch must not be blank");
        }
        if (patch.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
            return WorkspaceWriteResult.fail(path, "patch exceeds 1MB limit");
        }
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path does not map to a workspace repository");
        }
        try {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.patch",
                    Map.of("path", target.relativePath(), "expectedHash", expectedHash, "patch", patch),
                    TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return okResult(path, execution, expectedHash);
            }
            return writeFailure(path, execution, "patch failed");
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "patch failed: " + e.getMessage());
        }
    }

    /**
     * 透传 Worker file.write / file.patch 成功结果中的新 sha256 与 changed；
     * 兼容旧 Worker 缺少 changed 字段的情况，用写入前后的 SHA 做保守推断。
     */
    private static WorkspaceWriteResult okResult(String path, WorkerToolExecution execution, String expectedHash) {
        Map<String, Object> result = resultOf(execution);
        Object sha = result.get("sha256");
        String newSha256 = sha == null ? null : String.valueOf(sha);
        Object changedValue = result.get("changed");
        boolean changed;
        if (changedValue instanceof Boolean value) {
            changed = value;
        } else {
            // 兼容旧 Worker：旧版本没有返回 changed 时，用写入前后的 SHA 判断。
            changed = newSha256 != null && expectedHash != null
                    && !newSha256.equalsIgnoreCase(expectedHash);
        }
        return WorkspaceWriteResult.ok(path, newSha256, changed);
    }

    /**
     * 读取目标文件当前 sha256；文件不存在时返回空内容哈希。
     */
    private String currentHash(UUID workspaceId, WorkerPathResolver.Target target) {
        WorkerToolExecution read = executeTool(workspaceId, target.repositoryId(), "file.read",
                Map.of("path", target.relativePath(), "startLine", 1, "lineCount", 1), TOOL_TIMEOUT);
        if ("SUCCEEDED".equals(read.getStatus())) {
            Object sha = resultOf(read).get("sha256");
            if (sha != null && !String.valueOf(sha).isBlank()) {
                return String.valueOf(sha);
            }
        }
        return EMPTY_SHA256;
    }

    private WorkspaceDirectoryResult ensureParentDirectory(UUID workspaceId, WorkerPathResolver.Target target) {
        java.nio.file.Path parent = java.nio.file.Path.of(target.relativePath()).getParent();
        if (parent == null || parent.toString().isBlank()) {
            return WorkspaceDirectoryResult.ok(target.relativePath(), false);
        }
        String relativeParent = parent.toString().replace('\\', '/');
        try {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "directory.create",
                    Map.of("path", relativeParent), TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return WorkspaceDirectoryResult.ok(relativeParent,
                        Boolean.TRUE.equals(resultOf(execution).get("created")));
            }
            return directoryFailure(relativeParent, execution);
        } catch (RuntimeException e) {
            return WorkspaceDirectoryResult.infraFail(relativeParent, "directory creation failed: " + e.getMessage());
        }
    }

    /**
     * Worker 受控工具返回的参数、路径与内容冲突可回灌模型修正；执行超时、取消和未分类的
     * Worker 失败则属于基础设施错误。Worker 在 failureReason 前保留错误码，因此无需
     * 依赖易变的自然语言错误文本分类。
     */
    private static WorkspaceDirectoryResult directoryFailure(String path, WorkerToolExecution execution) {
        String reason = failureReason(execution, "directory creation failed");
        return isToolFailure(execution) ? WorkspaceDirectoryResult.fail(path, reason)
                : WorkspaceDirectoryResult.infraFail(path, reason);
    }

    private static WorkspaceWriteResult writeFailure(String path, WorkerToolExecution execution, String fallback) {
        String reason = failureReason(execution, fallback);
        return isToolFailure(execution) ? WorkspaceWriteResult.fail(path, reason)
                : WorkspaceWriteResult.infraFail(path, reason);
    }

    private static String failureReason(WorkerToolExecution execution, String fallback) {
        return execution.getFailureReason() == null || execution.getFailureReason().isBlank()
                ? fallback : execution.getFailureReason();
    }

    private static boolean isToolFailure(WorkerToolExecution execution) {
        if (!"FAILED".equals(execution.getStatus())) {
            return false;
        }
        String reason = execution.getFailureReason();
        return reason != null && (reason.startsWith("TOOL_")
                || reason.startsWith("FILE_HASH_MISMATCH")
                || reason.startsWith("FILE_PATCH_FAILED")
                || reason.startsWith("PATCH_"));
    }
}
