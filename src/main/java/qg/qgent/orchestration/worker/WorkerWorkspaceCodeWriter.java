package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;
import qg.qgent.service.TaskRunWorkerExecutionService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> RECOVERABLE_TOOL_FAILURE_CODES = Set.of(
            "FILE_HASH_MISMATCH", "FILE_PATCH_FAILED", "TOOL_ARGUMENT_INVALID", "TOOL_PATH_INVALID",
            "TOOL_NOT_SUPPORTED", "COMMAND_NOT_ALLOWED");

    /** 兼容无 Spring 容器的端口单元测试；生产装配使用带诊断持久化服务的构造器。 */
    public WorkerWorkspaceCodeWriter(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Autowired
    public WorkerWorkspaceCodeWriter(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties,
                                     TaskRunWorkerExecutionService workerExecutionService) {
        super(client, sessions, properties, workerExecutionService);
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
            return WorkspaceDirectoryResult.infraFail(path, "directory creation failed: " + exceptionDetail(e));
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
                        ? WorkspaceWriteResult.infraFail(path, parent.getFailureCode(), parent.getError())
                        : WorkspaceWriteResult.fail(path, parent.getFailureCode(), parent.getError());
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
            return WorkspaceWriteResult.infraFail(path, "write failed: " + exceptionDetail(e));
        }
    }

    @Override
    public WorkspaceWriteResult replaceFile(UUID workspaceId, String path, String expectedHash, String content) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            return WorkspaceWriteResult.fail(path, "expectedHash must be 64 hex chars");
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
            // file.write 在 expectedHash 非空时仍执行旧内容哈希校验，因此不会覆盖并发更新；
            // 非空 hash 也会阻止 replace_file 借道创建不存在的新文件。
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.write",
                    Map.of("path", target.relativePath(), "expectedHash", expectedHash, "content", content),
                    TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return okResult(path, execution, expectedHash);
            }
            return writeFailure(path, execution, "replace failed");
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "replace failed: " + exceptionDetail(e));
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
            return writeFailure(path, execution, "patch failed", "file.patch");
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "patch failed: " + exceptionDetail(e));
        }
    }

    @Override
    public WorkspaceWriteResult ensureTrailingNewline(UUID workspaceId, String path, String expectedHash) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            return WorkspaceWriteResult.fail(path, "expectedHash must be 64 hex chars");
        }
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path does not map to a workspace repository");
        }
        try {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(),
                    "file.ensure_trailing_newline",
                    Map.of("path", target.relativePath(), "expectedHash", expectedHash), TOOL_TIMEOUT);
            if ("SUCCEEDED".equals(execution.getStatus())) {
                return okResult(path, execution, expectedHash);
            }
            return writeFailure(path, execution, "ensure trailing newline failed", "file.ensure_trailing_newline");
        } catch (RuntimeException e) {
            return WorkspaceWriteResult.infraFail(path, "ensure trailing newline failed: " + exceptionDetail(e));
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
            return WorkspaceDirectoryResult.infraFail(relativeParent, "directory creation failed: " + exceptionDetail(e));
        }
    }

    /**
     * Worker 受控工具返回的参数、路径与内容冲突可回灌模型修正；执行超时、取消和未分类的
     * Worker 失败则属于基础设施错误。受控工具的可修复失败由稳定 failureCode 分类，
     * 不得依赖易变的自然语言错误文本。
     */
    private static WorkspaceDirectoryResult directoryFailure(String path, WorkerToolExecution execution) {
        String reason = failureReason(execution, "directory creation failed");
        return isToolFailure(execution) ? WorkspaceDirectoryResult.fail(path, execution.getFailureCode(), reason)
                : WorkspaceDirectoryResult.infraFail(path, execution.getFailureCode(), reason);
    }

    private static WorkspaceWriteResult writeFailure(String path, WorkerToolExecution execution, String fallback) {
        return writeFailure(path, execution, fallback, null);
    }

    private static WorkspaceWriteResult writeFailure(String path, WorkerToolExecution execution, String fallback,
                                                     String operation) {
        String failureCode = effectiveFailureCode(execution, operation);
        String reason = failureReason(execution,
                "FILE_PATCH_FAILED".equals(failureCode)
                        ? "补丁无法应用，请重新读取文件后重试" : fallback);
        return isToolFailure(execution, operation) ? WorkspaceWriteResult.fail(path, failureCode, reason)
                : WorkspaceWriteResult.infraFail(path, failureCode, reason);
    }

    private static String failureReason(WorkerToolExecution execution, String fallback) {
        return execution.getFailureReason() == null || execution.getFailureReason().isBlank()
                ? fallback : execution.getFailureReason();
    }

    /**
     * 异常消息兜底：消息为 null 或空白时退回异常类型名，避免把 {@code "null"} 拼进错误文案
     * 丢失诊断信息（此前 apply_patch 曾出现 {@code "patch failed: null"}，无法定位失败原因）。
     */
    private static String exceptionDetail(Throwable exception) {
        if (exception == null) {
            return "unknown error";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static boolean isToolFailure(WorkerToolExecution execution) {
        return isToolFailure(execution, null);
    }

    private static boolean isToolFailure(WorkerToolExecution execution, String operation) {
        if (!"FAILED".equals(execution.getStatus())) {
            return false;
        }
        String failureCode = effectiveFailureCode(execution, operation);
        if (RECOVERABLE_TOOL_FAILURE_CODES.contains(failureCode)) {
            return true;
        }
        // 兼容尚未部署 failureCode/failureReason 字段的旧 Worker：file.patch 的失败只能
        // 通过结构化工具结果回灌模型修复。其它工具不能按工具名猜测，避免把 Worker/网络故障
        // 错误降级成可重试的业务参数错误。
        return (failureCode == null || failureCode.isBlank())
                && "file.patch".equals(operation == null ? execution.getTool() : operation);
    }

    private static String effectiveFailureCode(WorkerToolExecution execution) {
        return effectiveFailureCode(execution, null);
    }

    private static String effectiveFailureCode(WorkerToolExecution execution, String operation) {
        if (execution == null) {
            return null;
        }
        if (execution.getFailureCode() != null && !execution.getFailureCode().isBlank()) {
            return execution.getFailureCode();
        }
        return "FAILED".equals(execution.getStatus())
                && "file.patch".equals(operation == null ? execution.getTool() : operation)
                ? "FILE_PATCH_FAILED" : null;
    }
}
