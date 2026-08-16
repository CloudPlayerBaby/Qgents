package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
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
            String expectedHash = currentHash(workspaceId, target);
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.write",
                    Map.of("path", target.relativePath(), "expectedHash", expectedHash, "content", content),
                    TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return WorkspaceWriteResult.ok(path);
            }
            return WorkspaceWriteResult.fail(path,
                    execution.getFailureReason() == null ? "write failed" : execution.getFailureReason());
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
                return WorkspaceWriteResult.ok(path);
            }
            return WorkspaceWriteResult.fail(path,
                    execution.getFailureReason() == null ? "patch failed" : execution.getFailureReason());
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "patch failed: " + e.getMessage());
        }
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
}
