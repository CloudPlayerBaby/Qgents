package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

/**
 * {@link SandboxSessionManager} 单元测试：验证 provision/创建 Sandbox 的字段映射、
 * 会话复用、release 销毁与未启用时的 no-op，不启动 Spring 上下文、不访问真实 Worker。
 */
class SandboxSessionManagerTest {

    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SandboxWorkerClient client;
    private WorkspaceMapper workspaceMapper;
    private WorkspaceRepositoryMapper repositoryMapper;
    private ProjectRepositoryMapper projectRepositoryMapper;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        repositoryMapper = mock(WorkspaceRepositoryMapper.class);
        projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
    }

    private SandboxSessionManager enabledManager() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setEnabled(true);
        return new SandboxSessionManager(client, properties, workspaceMapper, repositoryMapper,
                projectRepositoryMapper);
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(WORKSPACE);
        entity.setProjectId(PROJECT);
        entity.setStorageKey("workspaces/" + WORKSPACE);
        return entity;
    }

    private WorkspaceRepositoryEntity repository() {
        WorkspaceRepositoryEntity entity = new WorkspaceRepositoryEntity();
        entity.setWorkspaceId(WORKSPACE);
        entity.setProjectRepositoryId(REPO);
        entity.setWorkspacePath("repo-1");
        entity.setBaseCommit("main");
        entity.setSourceBranch("feat/task-" + TASK);
        return entity;
    }

    @Test
    void disabledManagerIsNoOp() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        SandboxSessionManager manager = new SandboxSessionManager(client, properties, workspaceMapper, repositoryMapper,
                projectRepositoryMapper);

        assertThat(manager.acquire(TASK, PROJECT, WORKSPACE)).isNull();
        manager.release(WORKSPACE);
        verify(client, never()).provisionWorkspace(any(), any());
        verify(client, never()).createSandbox(any());
    }

    @Test
    void acquireProvisionsAndCreatesSandbox() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        SandboxSession session = enabledManager().acquire(TASK, PROJECT, WORKSPACE);

        ArgumentCaptor<WorkerWorkspaceProvisionRequest> provisionCaptor =
                ArgumentCaptor.forClass(WorkerWorkspaceProvisionRequest.class);
        verify(client).provisionWorkspace(org.mockito.ArgumentMatchers.eq(WORKSPACE), provisionCaptor.capture());
        assertThat(provisionCaptor.getValue().getProjectId()).isEqualTo(PROJECT);
        assertThat(provisionCaptor.getValue().getRepositories()).hasSize(1);
        WorkerWorkspaceRepositoryRequest repo = provisionCaptor.getValue().getRepositories().get(0);
        assertThat(repo.getRepositoryId()).isEqualTo(REPO);
        assertThat(repo.getBaseRef()).isEqualTo("main");
        assertThat(repo.getSourceBranch()).isEqualTo("feat/task-" + TASK);
        assertThat(repo.getWorkspacePath()).isEqualTo("repo-1");

        ArgumentCaptor<WorkerCreateSandboxRequest> createCaptor =
                ArgumentCaptor.forClass(WorkerCreateSandboxRequest.class);
        verify(client).createSandbox(createCaptor.capture());
        WorkerCreateSandboxRequest create = createCaptor.getValue();
        assertThat(create.getWorkspaceStorageKey()).isEqualTo("workspaces/" + WORKSPACE);
        assertThat(create.getTaskRunId()).isEqualTo(TASK);
        assertThat(create.getRepositoryIds()).containsExactly(REPO);
        assertThat(create.getImageProfile()).isEqualTo("java-node");

        assertThat(session.getSandboxId()).isNotNull();
        assertThat(session.singleRepository()).isEqualTo(REPO);
        assertThat(session.getRepositoryByPath()).containsEntry("repo-1", REPO);
    }

    @Test
    void acquireReusesExistingSession() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        SandboxSessionManager manager = enabledManager();

        SandboxSession first = manager.acquire(TASK, PROJECT, WORKSPACE);
        SandboxSession second = manager.acquire(TASK, PROJECT, WORKSPACE);

        assertThat(second).isSameAs(first);
        verify(client).provisionWorkspace(any(), any());
        verify(client).createSandbox(any());
    }

    @Test
    void acquireFailsWhenRepositoryHasNoBaseRef() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        WorkspaceRepositoryEntity noBase = repository();
        noBase.setBaseCommit(null);
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(noBase));
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(null);

        assertThatThrownBy(() -> enabledManager().acquire(TASK, PROJECT, WORKSPACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no base ref");
        verify(client, never()).createSandbox(any());
    }

    @Test
    void acquireFallsBackToDefaultBranchWhenBaseCommitMissing() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        WorkspaceRepositoryEntity noBase = repository();
        noBase.setBaseCommit(null);
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(noBase));
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(REPO);
        binding.setDefaultBranch("main");
        when(projectRepositoryMapper.selectById(REPO)).thenReturn(binding);
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);

        enabledManager().acquire(TASK, PROJECT, WORKSPACE);

        ArgumentCaptor<WorkerWorkspaceProvisionRequest> captor =
                ArgumentCaptor.forClass(WorkerWorkspaceProvisionRequest.class);
        verify(client).provisionWorkspace(org.mockito.ArgumentMatchers.eq(WORKSPACE), captor.capture());
        assertThat(captor.getValue().getRepositories().get(0).getBaseRef()).isEqualTo("main");
    }

    @Test
    void requireThrowsWithoutSession() {
        SandboxSessionManager manager = enabledManager();
        assertThatThrownBy(() -> manager.require(WORKSPACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sandbox session");
    }

    @Test
    void releaseDestroysSandboxAndRemovesSession() {
        when(workspaceMapper.selectById(WORKSPACE)).thenReturn(workspace());
        when(repositoryMapper.selectByWorkspace(WORKSPACE)).thenReturn(List.of(repository()));
        WorkerWorkspace provisioned = new WorkerWorkspace();
        provisioned.setStorageKey("workspaces/" + WORKSPACE);
        when(client.provisionWorkspace(any(), any())).thenReturn(provisioned);
        SandboxSessionManager manager = enabledManager();
        SandboxSession session = manager.acquire(TASK, PROJECT, WORKSPACE);

        manager.release(WORKSPACE);

        verify(client).destroySandbox(session.getSandboxId());
        assertThatThrownBy(() -> manager.require(WORKSPACE))
                .isInstanceOf(IllegalStateException.class);
    }
}
