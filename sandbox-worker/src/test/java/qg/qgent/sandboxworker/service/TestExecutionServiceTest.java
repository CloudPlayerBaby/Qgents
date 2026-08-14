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
        properties.setImageProfiles(Set.of("java-node"));
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
        when(commands.execute(eq(allocation), eq("/workspace/backend"), eq(List.of("./mvnw", "test")),
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
}
