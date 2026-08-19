package qg.qgent.orchestration.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import qg.qgent.api.ApiException;

/**
 * {@link SandboxWorkerClient} 的契约测试：用 MockRestServiceServer 对齐
 * {@code contracts/sandbox-worker-openapi.yaml} 的请求路径与响应 JSON 形状，
 * 验证 DTO 字段映射与 Worker 错误码透传，不启动真实 Worker。
 */
class SandboxWorkerClientTest {

    private static final String BASE = "http://sandbox-worker";
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID EXECUTION = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SandboxWorkerClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SandboxWorkerClient(builder.build(), new ObjectMapper());
    }

    @Test
    void provisionsWorkspaceAndReadsStorageKey() {
        server.expect(once(), requestTo(BASE + "/internal/v1/workspaces/" + WORKSPACE))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("""
                        {"id":"%s","projectId":"00000000-0000-0000-0000-000000000009","storageKey":"workspaces/%s",
                         "status":"READY",
                         "repositories":[{"repositoryId":"%s","baseRef":"main","sourceBranch":"feat/login",
                         "workspacePath":"app","baseCommit":"abcdef0123456789","headCommit":null}],
                         "createdAt":"2026-08-13T00:00:00Z","updatedAt":"2026-08-13T00:00:00Z"}
                        """.formatted(WORKSPACE, WORKSPACE, REPO), MediaType.APPLICATION_JSON));

        WorkerWorkspaceProvisionRequest request = new WorkerWorkspaceProvisionRequest();
        request.setProjectId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        WorkerWorkspaceRepositoryRequest repo = new WorkerWorkspaceRepositoryRequest();
        repo.setRepositoryId(REPO);
        repo.setBaseRef("main");
        repo.setSourceBranch("feat/login");
        repo.setWorkspacePath("app");
        request.setRepositories(List.of(repo));

        WorkerWorkspace workspace = client.provisionWorkspace(WORKSPACE, request);

        assertEquals("READY", workspace.getStatus());
        assertEquals("workspaces/" + WORKSPACE, workspace.getStorageKey());
        assertEquals(1, workspace.getRepositories().size());
        assertEquals("app", workspace.getRepositories().get(0).getWorkspacePath());
        assertNull(workspace.getRepositories().get(0).getHeadCommit());
        server.verify();
    }

    @Test
    void createsSandboxAndReadsStatus() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"%s","taskRunId":"00000000-0000-0000-0000-000000000005","status":"READY",
                         "runtimeKind":"fake","createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(SANDBOX), MediaType.APPLICATION_JSON));

        WorkerCreateSandboxRequest request = new WorkerCreateSandboxRequest();
        request.setSandboxId(SANDBOX);
        request.setTaskRunId(UUID.fromString("00000000-0000-0000-0000-000000000005"));
        request.setWorkspaceStorageKey("workspaces/" + WORKSPACE);
        request.setImageProfile("dev-tools");
        request.setRepositoryIds(List.of(REPO));

        WorkerSandbox sandbox = client.createSandbox(request);

        assertEquals(SANDBOX, sandbox.getId());
        assertEquals("fake", sandbox.getRuntimeKind());
        server.verify();
    }

    @Test
    void createsControlledTestSnapshot() {
        UUID snapshot = UUID.fromString("00000000-0000-0000-0000-000000000008");
        UUID project = UUID.fromString("00000000-0000-0000-0000-000000000009");
        server.expect(once(), requestTo(BASE + "/internal/v1/workspaces/" + WORKSPACE
                        + "/repositories/" + REPO + "/test-snapshots/" + snapshot + "?projectId=" + project
                        + "&expectedHeadCommit=0123456789012345678901234567890123456789"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"%s","projectId":"%s","storageKey":"workspaces/%s","status":"READY",
                         "repositories":[],"createdAt":"2026-08-14T00:00:00Z","updatedAt":"2026-08-14T00:00:00Z"}
                        """.formatted(snapshot, project, snapshot), MediaType.APPLICATION_JSON));

        WorkerWorkspace result = client.createTestSnapshot(WORKSPACE, REPO, snapshot, project,
                "0123456789012345678901234567890123456789");

        assertEquals(snapshot, result.getId());
        server.verify();
    }

    @Test
    void resolvesGitRefToImmutableCommit() {
        String sha = "0123456789012345678901234567890123456789";
        server.expect(once(), requestTo(BASE + "/internal/v1/git-resolutions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"commitSha\":\"" + sha + "\"}", MediaType.APPLICATION_JSON));
        WorkerGitResolveRequest request = new WorkerGitResolveRequest();
        request.setRepositoryId(REPO); request.setRef("main");

        WorkerGitResolveResponse response = client.resolveGitRef(request);

        assertEquals(sha, response.getCommitSha());
        server.verify();
    }

    @Test
    void executesTestsetsThroughDedicatedWorkerOperation() {
        server.expect(once(), requestTo(BASE + "/internal/v1/test-executions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"executionId":"%s","status":"PASSED","resolvedHeadCommit":"abc",
                         "results":[{"testsetId":"%s","status":"PASSED","exitCode":0,"durationMs":12}]}
                        """.formatted(EXECUTION, REPO), MediaType.APPLICATION_JSON));
        WorkerTestExecutionRequest request = new WorkerTestExecutionRequest();
        request.setExecutionId(EXECUTION); request.setProjectId(UUID.randomUUID()); request.setRepositoryId(REPO);
        request.setWorkspaceId(WORKSPACE); request.setTestsets(List.of());

        WorkerTestExecutionResponse response = client.executeTests(request);

        assertEquals("PASSED", response.getStatus());
        assertEquals(1, response.getResults().size());
        server.verify();
    }

    @Test
    void submitsToolExecutionAndReadsQueuedResult() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/tool-executions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":"%s","ownerWorkerId":"local","sandboxId":"%s","repositoryId":"%s","tool":"file.read",
                         "status":"QUEUED","exitCode":null,"result":{},"failureCode":null,"failureReason":null,
                         "createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(EXECUTION, SANDBOX, REPO)));

        WorkerToolExecutionRequest request = new WorkerToolExecutionRequest();
        request.setExecutionId(EXECUTION);
        request.setRepositoryId(REPO);
        request.setTool("file.read");
        request.setArguments(Map.of("path", "src/App.java"));

        WorkerToolExecution execution = client.submitToolExecution(SANDBOX, request);

        assertEquals("QUEUED", execution.getStatus());
        assertEquals("file.read", execution.getTool());
        assertNull(execution.getExitCode());
        server.verify();
    }

    @Test
    void readsTerminalToolExecutionWithResult() {
        server.expect(once(), requestTo(BASE + "/internal/v1/tool-executions/" + EXECUTION))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerWorkerId":"local","sandboxId":"%s","repositoryId":"%s","tool":"file.read",
                         "status":"SUCCEEDED","exitCode":0,
                         "result":{"path":"src/App.java","sha256":"abc","startLine":1,"totalLines":3,
                         "lines":["a","b","c"],"truncated":false},
                         "failureCode":null,"failureReason":null,"createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(EXECUTION, SANDBOX, REPO), MediaType.APPLICATION_JSON));

        WorkerToolExecution execution = client.getToolExecution(EXECUTION);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(0, execution.getExitCode());
        assertNull(execution.getFailureCode());
        assertEquals(3, execution.getResult().get("totalLines"));
        assertEquals(List.of("a", "b", "c"), execution.getResult().get("lines"));
        server.verify();
    }

    @Test
    void readsTerminalToolExecutionWithStructuredFailureCode() {
        server.expect(once(), requestTo(BASE + "/internal/v1/tool-executions/" + EXECUTION))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerWorkerId":"local","sandboxId":"%s","repositoryId":"%s","tool":"file.patch",
                         "status":"FAILED","exitCode":null,"result":{},"failureCode":"FILE_PATCH_FAILED",
                         "failureReason":"hunk 声明行数与正文不一致","createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(EXECUTION, SANDBOX, REPO), MediaType.APPLICATION_JSON));

        WorkerToolExecution execution = client.getToolExecution(EXECUTION);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("FILE_PATCH_FAILED", execution.getFailureCode());
        assertEquals("hunk 声明行数与正文不一致", execution.getFailureReason());
        server.verify();
    }

    @Test
    void readsExecutionLogsWithCursor() {
        server.expect(once(),
                requestTo(BASE + "/internal/v1/tool-executions/" + EXECUTION + "/logs?after=0&limit=200"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"items":[{"sequence":1,"stream":"STDOUT","content":"Tests run: 1",
                         "timestamp":"2026-08-13T00:00:01Z"}],"nextCursor":1}
                        """, MediaType.APPLICATION_JSON));

        WorkerExecutionLogs logs = client.getToolExecutionLogs(EXECUTION, 0, 200);

        assertEquals(1, logs.getNextCursor());
        assertEquals(1, logs.getItems().size());
        assertEquals("STDOUT", logs.getItems().get(0).getStream());
        server.verify();
    }

    @Test
    void readsWorkspaceGitDiff() {
        server.expect(once(), requestTo(BASE + "/internal/v1/workspaces/" + WORKSPACE + "/repositories/" + REPO + "/git/diff"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"headCommit":"abcdef0123456789","diffHash":"sha256:abc","patch":"diff --git a/Foo.java b/Foo.java"}
                        """, MediaType.APPLICATION_JSON));

        WorkerGitDiff diff = client.createWorkspaceGitDiff(WORKSPACE, REPO);

        assertEquals("abcdef0123456789", diff.getHeadCommit());
        assertNotNull(diff.getPatch());
        server.verify();
    }

    @Test
    void preservesWorkerErrorCode() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/tool-executions"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"EXECUTION_ID_CONFLICT\",\"message\":\"执行编号已经存在\"}"));

        WorkerToolExecutionRequest request = new WorkerToolExecutionRequest();
        request.setExecutionId(EXECUTION);
        request.setTool("file.read");
        request.setArguments(Map.of("path", "a"));

        ApiException exception = assertThrows(ApiException.class,
                () -> client.submitToolExecution(SANDBOX, request));

        assertEquals("EXECUTION_ID_CONFLICT", exception.code());
        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("执行编号已经存在", exception.getMessage());
        server.verify();
    }

    @Test
    void syncGitStore() {
        server.expect(once(), requestTo(BASE + "/internal/v1/git-stores/" + REPO + "/sync"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"headCommit":"abcdef0123456789"}
                        """, MediaType.APPLICATION_JSON));

        WorkerGitStoreSyncRequest request = new WorkerGitStoreSyncRequest();
        request.setRepositoryUrl("https://github.com/owner/repo.git");
        request.setRemoteBranch("main");
        request.setExpectedHeadCommit("abcdef0123456789");
        request.setCredentialGrantId("mock-grant");

        WorkerGitStoreSyncResponse response = client.syncGitStore(REPO, request);

        assertEquals("abcdef0123456789", response.getHeadCommit());
        server.verify();
    }

    @Test
    void pushWorkspaceBranch() {
        server.expect(once(), requestTo(BASE + "/internal/v1/workspaces/" + WORKSPACE + "/repositories/" + REPO + "/git/push"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"branch":"feat/login","headCommit":"abcdef0123456789","verified":true}
                        """, MediaType.APPLICATION_JSON));

        WorkerGitPushRequest request = new WorkerGitPushRequest();
        request.setExpectedHeadCommit("abcdef0123456789");
        request.setCredentialGrantId("mock-grant");

        WorkerGitPushResponse response = client.pushWorkspaceBranch(WORKSPACE, REPO, request);

        assertEquals("feat/login", response.getBranch());
        assertEquals("abcdef0123456789", response.getHeadCommit());
        assertEquals(true, response.isVerified());
        server.verify();
    }

    @Test
    void renewsSandboxLeaseWithoutTtl() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/lease/renew"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"%s","taskRunId":"00000000-0000-0000-0000-000000000005","status":"READY",
                         "runtimeKind":"fake","createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(SANDBOX), MediaType.APPLICATION_JSON));

        WorkerSandbox sandbox = client.renewSandbox(SANDBOX);

        assertEquals(SANDBOX, sandbox.getId());
        assertEquals("READY", sandbox.getStatus());
        server.verify();
    }

    @Test
    void renewsSandboxLeaseWithTtlSeconds() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/lease/renew?ttlSeconds=60"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"%s","taskRunId":"00000000-0000-0000-0000-000000000005","status":"READY",
                         "runtimeKind":"fake","createdAt":"2026-08-13T00:00:00Z"}
                        """.formatted(SANDBOX), MediaType.APPLICATION_JSON));

        WorkerSandbox sandbox = client.renewSandbox(SANDBOX, 60L);

        assertEquals(SANDBOX, sandbox.getId());
        assertEquals("READY", sandbox.getStatus());
        server.verify();
    }

    @Test
    void renewsSandboxPreserves404() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/lease/renew"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SANDBOX_NOT_FOUND\",\"message\":\"sandbox is missing\"}"));

        ApiException exception = assertThrows(ApiException.class, () -> client.renewSandbox(SANDBOX));

        assertEquals("SANDBOX_NOT_FOUND", exception.code());
        assertEquals(HttpStatus.NOT_FOUND, exception.status());
        assertEquals("sandbox is missing", exception.getMessage());
        server.verify();
    }

    @Test
    void renewsSandboxMapsNetworkErrorToUnavailable() {
        server.expect(once(), requestTo(BASE + "/internal/v1/sandboxes/" + SANDBOX + "/lease/renew"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ApiException exception = assertThrows(ApiException.class, () -> client.renewSandbox(SANDBOX));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        server.verify();
    }
}
