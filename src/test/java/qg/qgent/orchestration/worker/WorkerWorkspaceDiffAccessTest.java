package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qg.qgent.orchestration.tool.GitDiffResult;

/**
 * {@link WorkerWorkspaceDiffAccess} 单测：逐仓库调用 git/diff 并聚合 patch 与分隔符。纯 Mock。
 */
class WorkerWorkspaceDiffAccessTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPO_A = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REPO_B = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SandboxWorkerClient client;
    private SandboxSessionManager sessions;
    private WorkerWorkspaceDiffAccess access;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        sessions = mock(SandboxSessionManager.class);
        access = new WorkerWorkspaceDiffAccess(client, sessions);
    }

    @Test
    void diffAggregatesRepositories() {
        Map<String, UUID> byPath = new LinkedHashMap<>();
        byPath.put("repo-a", REPO_A);
        byPath.put("repo-b", REPO_B);
        when(sessions.require(WORKSPACE)).thenReturn(new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX,
                "workspaces/" + WORKSPACE, List.of(REPO_A, REPO_B), byPath));
        when(client.createWorkspaceGitDiff(WORKSPACE, REPO_A))
                .thenReturn(new WorkerGitDiff("head-a", "sha256:a", "diff-a"));
        when(client.createWorkspaceGitDiff(WORKSPACE, REPO_B))
                .thenReturn(new WorkerGitDiff("head-b", "sha256:b", "diff-b"));

        GitDiffResult result = access.diff(WORKSPACE);

        assertThat(result.ok()).isTrue();
        assertThat(result.diff()).contains("===== repo-a =====").contains("diff-a")
                .contains("===== repo-b =====").contains("diff-b");
        assertThat(result.headCommit()).isEqualTo("head-a");
        assertThat(result.baseCommit()).isEqualTo("head-a");
    }
}
