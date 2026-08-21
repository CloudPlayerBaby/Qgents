package qg.qgent.sandboxworker.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.TestExecutionItemRequest;
import qg.qgent.sandboxworker.api.TestExecutionItemResponse;
import qg.qgent.sandboxworker.api.TestExecutionRequest;
import qg.qgent.sandboxworker.api.TestExecutionResponse;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;
import qg.qgent.sandboxworker.runtime.WorkspacePathResolver;
import qg.qgent.sandboxworker.workspace.WorkspaceManagerService;
import qg.qgent.sandboxworker.workspace.WorkspaceProvisionRequest;
import qg.qgent.sandboxworker.workspace.WorkspaceRepositoryRequest;
import qg.qgent.sandboxworker.workspace.WorkspaceResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 在受控 Sandbox 中同步执行一组 Testset，并始终清理本次 Sandbox。
 */
@Service
@RequiredArgsConstructor
public class TestExecutionService {
    private static final int MAX_FAILURE_SUMMARY_LENGTH = 500;
    private static final int FAILURE_SUMMARY_TAIL_LINES = 8;
    private static final int MAX_COMPILATION_DIAGNOSTICS = 4;
    private static final Pattern COMPILATION_DIAGNOSTIC = Pattern.compile(
            "(?i)(?:\\berror:|\\bexception:|\\bfailed to compile\\b)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\"']+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\"']*)?");
    private static final Pattern ENVIRONMENT_ASSIGNMENT = Pattern.compile(
            "\\b[A-Z][A-Z0-9_]{2,}\\s*=\\s*[^\\s,;}\\\"]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s,;\"']+");
    private static final Set<List<String>> ALLOWED_TEST_COMMANDS = Set.of(
            List.of("mvn", "test"),
            List.of("gradle", "test"),
            List.of("npm", "test"),
            List.of("sh", "./mvnw", "test"),
            List.of("sh", "./gradlew", "test"));
    private final WorkspaceManagerService workspaces;
    private final SandboxService sandboxes;
    private final CommandExecutor commands;
    private final WorkspacePathResolver paths;
    private final SandboxWorkerProperties properties;

    public TestExecutionResponse execute(TestExecutionRequest request) {
        if (!"docker".equalsIgnoreCase(properties.getRuntime())) {
            throw new WorkerException(HttpStatus.SERVICE_UNAVAILABLE, "REAL_SANDBOX_REQUIRED",
                    "TestRun/DryRun 只能由真实 Docker Sandbox 产生通过结果");
        }
        boolean hasWorkspace = request.getWorkspaceId() != null;
        boolean hasRef = request.getRef() != null && !request.getRef().isBlank();
        if (hasWorkspace == hasRef) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TEST_EXECUTION_TARGET",
                    "workspaceId 与 ref 必须二选一");
        }
        UUID workspaceId = hasWorkspace ? request.getWorkspaceId() : UUID.nameUUIDFromBytes(
                ("qgents-test-execution:" + request.getExecutionId()).getBytes(StandardCharsets.UTF_8));
        boolean temporary = !hasWorkspace;
        String temporaryBranch = temporary ? "qgents-test-" + request.getExecutionId() : null;
        UUID sandboxId = UUID.randomUUID();
        try {
            String resolvedSource = null;
            String resolvedTarget = null;
            if (temporary) workspaces.cleanupTemporary(workspaceId, request.getRepositoryId(), temporaryBranch);
            WorkspaceResponse workspace = temporary ? provisionTemporary(workspaceId, request) : workspaces.get(workspaceId);
            if (!request.getProjectId().equals(workspace.getProjectId()) || workspace.getRepositories().stream()
                    .noneMatch(repository -> request.getRepositoryId().equals(repository.getRepositoryId()))) {
                throw new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_REPOSITORY_NOT_FOUND",
                        "Workspace 不属于当前项目或不包含目标仓库");
            }
            if (request.getMergeSourceRef() != null && !request.getMergeSourceRef().isBlank()) {
                if (!temporary) {
                    throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "MERGE_TEST_REQUIRES_TEMPORARY_WORKSPACE",
                            "合并门禁只能在一次性 Workspace 中执行");
                }
                resolvedSource = workspaces.mergeForTest(workspaceId, request.getRepositoryId(), request.getMergeSourceRef());
                resolvedTarget = request.getRef();
                workspace = workspaces.get(workspaceId);
            }
            CreateSandboxRequest create = new CreateSandboxRequest();
            create.setSandboxId(sandboxId);
            create.setTaskRunId(request.getExecutionId());
            create.setWorkspaceStorageKey(workspace.getStorageKey());
            create.setImageProfile(properties.getImageProfiles().contains("dev-tools") ? "dev-tools"
                    : properties.getImageProfiles().stream().findFirst().orElse("dev-tools"));
            create.setRepositoryIds(List.of(request.getRepositoryId()));
            sandboxes.create(create);
            SandboxAllocation allocation = sandboxes.findAllocation(sandboxId);
            String resolvedHead = workspace.getRepositories().stream()
                    .filter(repository -> request.getRepositoryId().equals(repository.getRepositoryId()))
                    .findFirst().orElseThrow().getHeadCommit();
            List<TestExecutionItemResponse> results = new ArrayList<>();
            for (TestExecutionItemRequest testset : request.getTestsets()) {
                results.add(run(allocation, request.getRepositoryId(), testset));
            }
            String status = results.stream().allMatch(item -> "PASSED".equals(item.getStatus())) ? "PASSED" : "FAILED";
            return new TestExecutionResponse(request.getExecutionId(), status, resolvedHead, resolvedSource,
                    resolvedTarget, List.copyOf(results));
        } finally {
            try {
                sandboxes.destroy(sandboxId);
            } catch (RuntimeException ignored) {
            }
            if (temporary) {
                try {
                    workspaces.cleanupTemporary(workspaceId, request.getRepositoryId(), temporaryBranch);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private WorkspaceResponse provisionTemporary(UUID workspaceId, TestExecutionRequest request) {
        WorkspaceRepositoryRequest repository = new WorkspaceRepositoryRequest();
        repository.setRepositoryId(request.getRepositoryId());
        repository.setBaseRef(request.getRef());
        repository.setSourceBranch("qgents-test-" + request.getExecutionId());
        repository.setWorkspacePath("repository");
        WorkspaceProvisionRequest provision = new WorkspaceProvisionRequest();
        provision.setProjectId(request.getProjectId());
        provision.setRepositories(List.of(repository));
        return workspaces.provision(workspaceId, provision);
    }

    private TestExecutionItemResponse run(SandboxAllocation allocation, UUID repositoryId,
                                          TestExecutionItemRequest testset) {
        long started = System.nanoTime();
        try {
            List<String> command = normalizeWrapperCommand(splitCommand(testset.getCommand()));
            if (!ALLOWED_TEST_COMMANDS.contains(command)) {
                throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "TEST_COMMAND_NOT_ALLOWED",
                        "Testset 命令不在受控测试命令白名单内");
            }
            Duration requested = Duration.ofSeconds(testset.getTimeoutSeconds());
            Duration timeout = requested.compareTo(properties.getMaxExecutionTimeout()) <= 0
                    ? requested : properties.getMaxExecutionTimeout();
            CommandExecutionResult result = commands.execute(allocation,
                    paths.resolveRepositoryContainer(allocation, repositoryId), command, timeout);
            if (result.getExitCode() == 126 || result.getExitCode() == 127) {
                return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", result.getExitCode(),
                        elapsed(started), "BUILD_ENVIRONMENT_UNAVAILABLE", "构建环境无法启动选定的测试命令");
            }
            boolean passed = result.getExitCode() == testset.getExpectedExitCode();
            return new TestExecutionItemResponse(testset.getTestsetId(), passed ? "PASSED" : "FAILED",
                    result.getExitCode(), elapsed(started), passed ? null : "UNEXPECTED_EXIT_CODE",
                    passed ? null : failureMessage(result));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started), "TIMED_OUT",
                    "测试执行超时");
        } catch (RuntimeException exception) {
            if (exception instanceof WorkerException workerException
                    && "TEST_COMMAND_NOT_ALLOWED".equals(workerException.getCode())) {
                return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started),
                        "TEST_COMMAND_NOT_ALLOWED", "Testset 命令不在受控测试白名单内");
            }
            if (exception instanceof WorkerException workerException
                    && "DOCKER_EXEC_FAILED".equals(workerException.getCode())) {
                return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started),
                        "DOCKER_EXEC_FAILED", "Sandbox 内测试命令未能启动");
            }
            return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started),
                    "EXECUTION_FAILED", "测试执行未能完成");
        }
    }

    /**
     * 只保留命令输出尾部的有限诊断信息，并移除凭据、环境变量、端点和宿主机路径。
     * Maven/Gradle 的失败原因通常位于输出末尾；完整原始输出不能进入用户可见结果。
     */
    static String failureMessage(CommandExecutionResult result) {
        List<String> lines = new ArrayList<>();
        appendCompilationDiagnostics(lines, result == null ? null : result.getStandardError());
        appendCompilationDiagnostics(lines, result == null ? null : result.getStandardOutput());
        appendTail(lines, result == null ? null : result.getStandardError());
        appendTail(lines, result == null ? null : result.getStandardOutput());
        String detail = String.join(" | ", lines).replaceAll("[\\r\\n]+", " ").strip();
        detail = BEARER.matcher(detail).replaceAll("Bearer [redacted]");
        detail = SENSITIVE_VALUE.matcher(detail).replaceAll("$1=[redacted]");
        detail = WINDOWS_HOST_PATH.matcher(detail).replaceAll("[host path omitted]");
        detail = UNIX_HOST_PATH.matcher(detail).replaceAll("[host path omitted]");
        detail = ENVIRONMENT_ASSIGNMENT.matcher(detail).replaceAll("[environment omitted]");
        detail = URL.matcher(detail).replaceAll("[endpoint omitted]");
        if (detail.isBlank()) {
            return "测试命令退出码与 Testset 预期不一致";
        }
        String message = "测试命令退出码与 Testset 预期不一致：" + detail;
        return message.length() <= MAX_FAILURE_SUMMARY_LENGTH
                ? message : message.substring(0, MAX_FAILURE_SUMMARY_LENGTH - 3) + "...";
    }

    private static void appendTail(List<String> destination, List<String> source) {
        if (source == null || source.isEmpty()) return;
        int start = Math.max(0, source.size() - FAILURE_SUMMARY_TAIL_LINES);
        for (int index = start; index < source.size(); index++) {
            String line = source.get(index);
            if (line != null && !line.isBlank()) destination.add(line.strip());
        }
    }

    /**
     * Gradle 的汇总页脚会掩盖 javac 的实际报错；优先保留有限数量的诊断行，仍交由
     * {@link #failureMessage(CommandExecutionResult)} 统一脱敏和截断。
     */
    private static void appendCompilationDiagnostics(List<String> destination, List<String> source) {
        if (source == null || source.isEmpty() || destination.size() >= MAX_COMPILATION_DIAGNOSTICS) return;
        for (String line : source) {
            if (line != null && COMPILATION_DIAGNOSTIC.matcher(line).find()) {
                destination.add(line.strip());
                if (destination.size() >= MAX_COMPILATION_DIAGNOSTICS) return;
            }
        }
    }

    /**
     * 支持普通空格、单引号和双引号；拒绝未闭合引号，不经 shell。
     */
    static List<String> splitCommand(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(current);
            }
        }
        if (quote != 0)
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "TEST_COMMAND_INVALID", "测试命令引号未闭合");
        if (!token.isEmpty()) result.add(token.toString());
        if (result.isEmpty() || result.size() > 64) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "TEST_COMMAND_INVALID", "测试命令参数数量无效");
        }
        return List.copyOf(result);
    }

    /**
     * 兼容历史 Testset 中没有 ./ 前缀的 Wrapper 命令，并通过 sh 启动 Wrapper，
     * 规避 Git worktree 没有 executable bit 时的 126。仅转换固定 Wrapper 向量，
     * 不接受 shell 字符串或任意 shell 选项。
     */
    static List<String> normalizeWrapperCommand(List<String> command) {
        if (command.equals(List.of("gradlew", "test")) || command.equals(List.of("./gradlew", "test"))) {
            return List.of("sh", "./gradlew", "test");
        }
        if (command.equals(List.of("mvnw", "test")) || command.equals(List.of("./mvnw", "test"))) {
            return List.of("sh", "./mvnw", "test");
        }
        return command;
    }

    private long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
