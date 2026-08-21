package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.tool.DisabledExecutionPort;
import qg.qgent.orchestration.tool.DisabledWorkspaceDiffAccess;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.LocalGitDiffAccess;
import qg.qgent.orchestration.tool.LocalSandboxExecutionPort;
import qg.qgent.orchestration.tool.LocalWorkspaceCodeAccess;
import qg.qgent.orchestration.tool.LocalWorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.TaskRunWorkerExecutionService;
import qg.qgent.service.WorkspaceService;

/**
 * 验证 {@code app.worker.enabled} 开关在两个模式下选中的端口实现：
 * 关闭时用本地 Local*（ExecutionPort/DiffAccess 通过 @Primary 压制 Disabled*），
 * 开启时用 Worker*。纯 Bean 装配检查，不启动完整 Spring Boot、不访问 DB/Redis。
 */
class WorkerPortSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SandboxWorkerClient.class, () -> mock(SandboxWorkerClient.class))
            .withBean(SandboxSessionManager.class, () -> mock(SandboxSessionManager.class))
            .withBean(SandboxWorkerProperties.class, SandboxWorkerProperties::new)
            .withBean(WorkspaceRepositoryMapper.class, () -> mock(WorkspaceRepositoryMapper.class))
            .withBean(WorkspaceService.class, () -> new WorkspaceService(mock(WorkspaceMapper.class), null))
            .withBean(TaskRunWorkerExecutionService.class, () -> mock(TaskRunWorkerExecutionService.class))
            .withUserConfiguration(Ports.class);

    @Test
    void localPortsSelectedWhenWorkerDisabled() {
        runner.withPropertyValues("app.worker.enabled=false").run(context -> {
            assertThat(context.getBean(ExecutionPort.class)).isInstanceOf(LocalSandboxExecutionPort.class);
            assertThat(context.getBean(WorkspaceCodeAccess.class)).isInstanceOf(LocalWorkspaceCodeAccess.class);
            assertThat(context.getBean(WorkspaceCodeWriter.class)).isInstanceOf(LocalWorkspaceCodeWriter.class);
            assertThat(context.getBean(WorkspaceDiffAccess.class)).isInstanceOf(LocalGitDiffAccess.class);
        });
    }

    @Test
    void workerPortsSelectedWhenWorkerEnabled() {
        runner.withPropertyValues("app.worker.enabled=true").run(context -> {
            assertThat(context.getBean(ExecutionPort.class)).isInstanceOf(WorkerSandboxExecutionPort.class);
            assertThat(context.getBean(WorkspaceCodeAccess.class)).isInstanceOf(WorkerWorkspaceCodeAccess.class);
            assertThat(context.getBean(WorkspaceCodeWriter.class)).isInstanceOf(WorkerWorkspaceCodeWriter.class);
            assertThat(context.getBean(WorkspaceDiffAccess.class)).isInstanceOf(WorkerWorkspaceDiffAccess.class);
        });
    }

    @Configuration
    @Import({
            LocalWorkspaceCodeAccess.class, LocalWorkspaceCodeWriter.class,
            LocalSandboxExecutionPort.class, LocalGitDiffAccess.class,
            DisabledExecutionPort.class, DisabledWorkspaceDiffAccess.class,
            WorkerWorkspaceCodeAccess.class, WorkerWorkspaceCodeWriter.class,
            WorkerSandboxExecutionPort.class, WorkerWorkspaceDiffAccess.class })
    static class Ports {
    }
}
