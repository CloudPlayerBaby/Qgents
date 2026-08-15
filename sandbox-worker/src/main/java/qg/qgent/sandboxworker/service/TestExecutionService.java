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
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * 在受控 Sandbox 中同步执行一组 Testset，并始终清理本次 Sandbox。
 */
@Service
@RequiredArgsConstructor
public class TestExecutionService {
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
                workspaces.mergeForTest(workspaceId, request.getRepositoryId(), request.getMergeSourceRef());
                workspace = workspaces.get(workspaceId);
            }
            CreateSandboxRequest create = new CreateSandboxRequest();
            create.setSandboxId(sandboxId);
            create.setTaskRunId(request.getExecutionId());
            create.setWorkspaceStorageKey(workspace.getStorageKey());
            create.setImageProfile(properties.getImageProfiles().stream().findFirst().orElse("java-node"));
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
            return new TestExecutionResponse(request.getExecutionId(), status, resolvedHead, List.copyOf(results));
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
            List<String> command = splitCommand(testset.getCommand());
            Duration requested = Duration.ofSeconds(testset.getTimeoutSeconds());
            Duration timeout = requested.compareTo(properties.getMaxExecutionTimeout()) <= 0
                    ? requested : properties.getMaxExecutionTimeout();
            CommandExecutionResult result = commands.execute(allocation,
                    paths.resolveRepositoryContainer(allocation, repositoryId), command, timeout);
            boolean passed = result.getExitCode() == testset.getExpectedExitCode();
            return new TestExecutionItemResponse(testset.getTestsetId(), passed ? "PASSED" : "FAILED",
                    result.getExitCode(), elapsed(started), passed ? null : "UNEXPECTED_EXIT_CODE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started), "TIMED_OUT");
        } catch (RuntimeException exception) {
            return new TestExecutionItemResponse(testset.getTestsetId(), "FAILED", null, elapsed(started),
                    "EXECUTION_FAILED");
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

    private long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
