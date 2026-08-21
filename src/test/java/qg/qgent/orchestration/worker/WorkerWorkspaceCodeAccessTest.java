package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link WorkerWorkspaceCodeAccess} 形状翻译单测：递归 list 扁平化、file.read 分页重组、
 * file.search 命中路径提取与路径→仓库解析。纯 Mock，不启动 Spring、不访问真实 Worker。
 */
class WorkerWorkspaceCodeAccessTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPO_A = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REPO_B = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SandboxWorkerClient client;
    private SandboxSessionManager sessions;
    private WorkerWorkspaceCodeAccess access;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        sessions = mock(SandboxSessionManager.class);
        access = new WorkerWorkspaceCodeAccess(client, sessions, new SandboxWorkerProperties());
    }

    private SandboxSession session() {
        return new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX, "workspaces/" + WORKSPACE,
                List.of(REPO_A, REPO_B), Map.of("repo-a", REPO_A, "repo-b", REPO_B));
    }

    /** 按请求构造终态结果：submit 时记录执行编号，poll 时按编号返回。 */
    private void stubToolExecution(Function<WorkerToolExecutionRequest, WorkerToolExecution> terminal) {
        Map<UUID, WorkerToolExecution> byId = new HashMap<>();
        when(client.submitToolExecution(any(), any())).thenAnswer(inv -> {
            WorkerToolExecutionRequest request = inv.getArgument(1);
            WorkerToolExecution queued = new WorkerToolExecution();
            queued.setId(request.getExecutionId());
            queued.setStatus("QUEUED");
            byId.put(request.getExecutionId(), terminal.apply(request));
            return queued;
        });
        when(client.getToolExecution(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));
    }

    @Test
    void listFilesFlattensAcrossRepositoriesAndSkipsIgnoredDirs() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            String dir = String.valueOf(request.getArguments().get("path"));
            List<Map<String, Object>> items;
            if (REPO_A.equals(request.getRepositoryId())) {
                items = ".".equals(dir)
                        ? List.of(Map.of("name", "src", "directory", true),
                                Map.of("name", "README.md", "directory", false))
                        : List.of(Map.of("name", "A.java", "directory", false));
            } else {
                items = List.of(Map.of("name", "B.java", "directory", false),
                        Map.of("name", "target", "directory", true));
            }
            execution.setResult(Map.of("path", dir, "items", items));
            return execution;
        });

        assertThat(access.listFiles(WORKSPACE))
                .containsExactly("repo-a/README.md", "repo-a/src/A.java", "repo-b/B.java");
    }

    @Test
    void listFilesIncludesEmptyDirectoriesAsPaths() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            String dir = String.valueOf(request.getArguments().get("path"));
            List<Map<String, Object>> items;
            if (REPO_B.equals(request.getRepositoryId())) {
                items = List.of();
            } else if (".".equals(dir)) {
                items = List.of(Map.of("name", "empty-folder", "directory", true),
                        Map.of("name", "src", "directory", true));
            } else if ("src".equals(dir)) {
                items = List.of(Map.of("name", "A.java", "directory", false));
            } else {
                // empty-folder 递归：没有子项 → 空目录
                items = List.of();
            }
            execution.setResult(Map.of("path", dir, "items", items));
            return execution;
        });

        assertThat(access.listFiles(WORKSPACE))
                .containsExactly("repo-a/empty-folder", "repo-a/src/A.java");
    }

    @Test
    void readFileResolvesPathAndReconstructsLines() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            execution.setResult(Map.of("path", request.getArguments().get("path"),
                    "sha256", "abc", "startLine", 1, "totalLines", 3,
                    "lines", List.of("a", "b", "c"), "truncated", false));
            return execution;
        });

        WorkspaceFileReadResult read = access.readFile(WORKSPACE, "repo-a/src/Foo.java");

        assertThat(read.isOk()).isTrue();
        assertThat(read.getContent()).isEqualTo("a\nb\nc");
        assertThat(read.getSha256()).isEqualTo("abc");
        ArgumentCaptor<WorkerToolExecutionRequest> captor =
                ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client).submitToolExecution(any(), captor.capture());
        assertThat(captor.getValue().getRepositoryId()).isEqualTo(REPO_A);
        assertThat(captor.getValue().getArguments().get("path")).isEqualTo("src/Foo.java");
    }

    @Test
    void searchCodeExtractsPathsFromMatches() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            List<String> matches = REPO_A.equals(request.getRepositoryId())
                    ? List.of("src/Foo.java:12:foo()", "src/Foo.java:20:foo")
                    : List.of("Bar.java:1:foo");
            execution.setResult(Map.of("matches", matches));
            return execution;
        });

        assertThat(access.searchCode(WORKSPACE, "foo"))
                .containsExactly("repo-a/src/Foo.java", "repo-b/Bar.java");
    }
}
