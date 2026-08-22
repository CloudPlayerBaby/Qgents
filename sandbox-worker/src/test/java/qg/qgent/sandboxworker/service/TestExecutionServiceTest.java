package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.api.TestExecutionItemRequest;
import qg.qgent.sandboxworker.api.TestExecutionRequest;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;
import qg.qgent.sandboxworker.runtime.WorkspacePathResolver;
import qg.qgent.sandboxworker.workspace.WorkspaceManagerService;
import qg.qgent.sandboxworker.workspace.WorkspaceRepositoryResponse;
import qg.qgent.sandboxworker.workspace.WorkspaceResponse;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestExecutionServiceTest {
    @Test
    void splitsQuotedCommandWithoutInvokingShell() {
        assertEquals(List.of("./mvnw", "-Dtest=Login Test", "test"),
                TestExecutionService.splitCommand("./mvnw \"-Dtest=Login Test\" test"));
    }

    @Test
    void rejectsUnclosedQuotes() {
        WorkerException error = assertThrows(WorkerException.class,
                () -> TestExecutionService.splitCommand("./mvnw \"test"));
        assertEquals("TEST_COMMAND_INVALID", error.getCode());
    }

    @Test
    void executesCommandAppliesTimeoutAndDestroysSandbox() throws Exception {
        WorkspaceManagerService workspaces = mock(WorkspaceManagerService.class);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutor commands = mock(CommandExecutor.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setRuntime("docker");
        properties.setImageProfiles(Set.of("dev-tools"));
        properties.setMaxExecutionTimeout(Duration.ofSeconds(60));
        TestExecutionService service = new TestExecutionService(workspaces, sandboxes, commands, paths, properties);
        UUID projectId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        WorkspaceRepositoryResponse repository = new WorkspaceRepositoryResponse(repositoryId, "backend", "feat/x",
                "main", "base", "head");
        when(workspaces.get(workspaceId)).thenReturn(new WorkspaceResponse(workspaceId, projectId,
                "workspaces/" + workspaceId, "READY", List.of(repository), "now", "now"));
        SandboxAllocation allocation = mock(SandboxAllocation.class);
        when(sandboxes.findAllocation(any())).thenReturn(allocation);
        when(paths.resolveRepositoryContainer(allocation, repositoryId)).thenReturn("/workspace/backend");
        when(commands.execute(eq(allocation), eq("/workspace/backend"), eq(List.of("sh", "./mvnw", "test")),
                eq(Duration.ofSeconds(60))))
                .thenReturn(new CommandExecutionResult(0, List.of(), List.of()));
        TestExecutionItemRequest item = new TestExecutionItemRequest();
        item.setTestsetId(UUID.randomUUID()); item.setCommand("./mvnw test"); item.setTimeoutSeconds(120);
        item.setPassRuleType("EXIT_CODE"); item.setExpectedExitCode(0);
        TestExecutionRequest request = new TestExecutionRequest(); request.setExecutionId(UUID.randomUUID());
        request.setProjectId(projectId); request.setRepositoryId(repositoryId); request.setWorkspaceId(workspaceId);
        request.setTestsets(List.of(item));

        var response = service.execute(request);

        assertEquals("PASSED", response.getStatus());
        assertEquals("head", response.getResolvedHeadCommit());
        verify(sandboxes).destroy(any());
    }

    @Test
    void fakeRuntimeCannotProducePassingTestResult() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setRuntime("fake");
        TestExecutionService service = new TestExecutionService(mock(WorkspaceManagerService.class),
                mock(SandboxService.class), mock(CommandExecutor.class), mock(WorkspacePathResolver.class), properties);
        TestExecutionRequest request = new TestExecutionRequest();
        WorkerException error = assertThrows(WorkerException.class, () -> service.execute(request));
        assertEquals("REAL_SANDBOX_REQUIRED", error.getCode());
    }

    @Test
    void preservesDockerStartupFailureCodeForValidTestCommand() throws Exception {
        WorkspaceManagerService workspaces = mock(WorkspaceManagerService.class);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutor commands = mock(CommandExecutor.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setRuntime("docker");
        properties.setImageProfiles(Set.of("dev-tools"));
        properties.setMaxExecutionTimeout(Duration.ofSeconds(60));
        TestExecutionService service = new TestExecutionService(workspaces, sandboxes, commands, paths, properties);
        UUID projectId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        WorkspaceRepositoryResponse repository = new WorkspaceRepositoryResponse(repositoryId, "repository", "main",
                "main", "base", "head");
        when(workspaces.get(workspaceId)).thenReturn(new WorkspaceResponse(workspaceId, projectId,
                "workspaces/" + workspaceId, "READY", List.of(repository), "now", "now"));
        SandboxAllocation allocation = mock(SandboxAllocation.class);
        when(sandboxes.findAllocation(any())).thenReturn(allocation);
        when(paths.resolveRepositoryContainer(allocation, repositoryId)).thenReturn("/workspace/repository");
        when(commands.execute(eq(allocation), eq("/workspace/repository"), eq(List.of("sh", "./gradlew", "test")),
                eq(Duration.ofSeconds(60))))
                .thenThrow(new WorkerException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "DOCKER_EXEC_FAILED", "Docker Exec 执行失败"));

        TestExecutionItemRequest item = new TestExecutionItemRequest();
        item.setTestsetId(UUID.randomUUID());
        item.setCommand("./gradlew test");
        item.setTimeoutSeconds(60);
        item.setPassRuleType("EXIT_CODE");
        item.setExpectedExitCode(0);
        TestExecutionRequest request = new TestExecutionRequest();
        request.setExecutionId(UUID.randomUUID());
        request.setProjectId(projectId);
        request.setRepositoryId(repositoryId);
        request.setWorkspaceId(workspaceId);
        request.setTestsets(List.of(item));

        var response = service.execute(request);

        assertEquals("FAILED", response.getStatus());
        assertEquals("DOCKER_EXEC_FAILED", response.getResults().getFirst().getFailureCode());
        assertNull(response.getResults().getFirst().getExitCode());
        verify(sandboxes).destroy(any());
    }

    @Test
    void executesWhitelistedFrontendLintCommand() throws Exception {
        WorkspaceManagerService workspaces = mock(WorkspaceManagerService.class);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutor commands = mock(CommandExecutor.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setRuntime("docker");
        properties.setImageProfiles(Set.of("dev-tools"));
        properties.setMaxExecutionTimeout(Duration.ofSeconds(60));
        TestExecutionService service = new TestExecutionService(workspaces, sandboxes, commands, paths, properties);
        UUID projectId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        WorkspaceRepositoryResponse repository = new WorkspaceRepositoryResponse(repositoryId, "frontend", "feat/x",
                "main", "base", "head");
        when(workspaces.get(workspaceId)).thenReturn(new WorkspaceResponse(workspaceId, projectId,
                "workspaces/" + workspaceId, "READY", List.of(repository), "now", "now"));
        SandboxAllocation allocation = mock(SandboxAllocation.class);
        when(sandboxes.findAllocation(any())).thenReturn(allocation);
        when(paths.resolveRepositoryContainer(allocation, repositoryId)).thenReturn("/workspace/frontend");
        when(commands.execute(eq(allocation), eq("/workspace/frontend"), eq(List.of("npm", "run", "lint")),
                eq(Duration.ofSeconds(60))))
                .thenReturn(new CommandExecutionResult(0, List.of(), List.of()));
        TestExecutionItemRequest item = new TestExecutionItemRequest();
        item.setTestsetId(UUID.randomUUID()); item.setCommand("npm run lint"); item.setTimeoutSeconds(60);
        item.setPassRuleType("EXIT_CODE"); item.setExpectedExitCode(0);
        TestExecutionRequest request = new TestExecutionRequest(); request.setExecutionId(UUID.randomUUID());
        request.setProjectId(projectId); request.setRepositoryId(repositoryId); request.setWorkspaceId(workspaceId);
        request.setTestsets(List.of(item));

        var response = service.execute(request);

        assertEquals("PASSED", response.getStatus());
        verify(commands).execute(eq(allocation), eq("/workspace/frontend"), eq(List.of("npm", "run", "lint")),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void normalizesHistoricalBareWrapperCommands() {
        assertEquals(List.of("sh", "./gradlew", "test"),
                TestExecutionService.normalizeWrapperCommand(List.of("gradlew", "test")));
        assertEquals(List.of("sh", "./mvnw", "test"),
                TestExecutionService.normalizeWrapperCommand(List.of("./mvnw", "test")));
    }

    @Test
    void failureMessageIncludesSafeTailOfMavenOutput() {
        String message = TestExecutionService.failureMessage(new CommandExecutionResult(1,
                List.of("BUILD FAILURE", "password=top-secret"),
                List.of("[ERROR] Tests run: 3, Failures: 1", "at C:\\workspace\\project\\Test.java:10")));

        assertTrue(message.contains("Tests run: 3, Failures: 1"));
        assertTrue(message.contains("[redacted]"));
        assertTrue(message.contains("[host path omitted]"));
        assertFalse(message.contains("top-secret"));
        assertTrue(message.length() <= 500);
    }

    @Test
    void failureMessagePrioritizesJavaCompilationErrorsBeforeGradleFooter() {
        String message = TestExecutionService.failureMessage(new CommandExecutionResult(1,
                List.of("/workspace/app/src/Main.java:12: error: incompatible types: String cannot be converted to int",
                        "2 errors", "BUILD FAILED in 3m 14s", "> Task :app:compileDebugJavaWithJavac FAILED"),
                List.of()));

        assertTrue(message.contains("error: incompatible types"));
        assertTrue(message.indexOf("error: incompatible types") < message.indexOf("BUILD FAILED"));
        assertTrue(message.length() <= 500);
    }
}
