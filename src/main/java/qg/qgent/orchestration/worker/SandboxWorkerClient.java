package qg.qgent.orchestration.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import qg.qgent.api.ApiException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 后端2 Sandbox Worker（{@code /internal/v1}）的受控 HTTP 客户端。
 * <p>
 * 只做协议调用与错误映射，不含任何业务编排：Workspace 准备、Sandbox 生命周期与工具执行
 * 均由上层（会话管理器 / 端口实现）按序驱动。所有方法：
 * <ul>
 *   <li>传输层失败（连接失败、超时）统一映射为 {@code SANDBOX_WORKER_UNAVAILABLE}（502）；</li>
 *   <li>Worker 返回的非 2xx 响应解析 {@code {code,message}}，保留 Worker 的业务错误码，
 *       供端口区分工具级失败（可回灌 LLM 纠正）与基础设施失败。</li>
 * </ul>
 * 本客户端不向 Worker 提交宿主机路径、Git 远端或凭证。
 */
@Slf4j
public class SandboxWorkerClient {

    private static final String BASE_PATH = "/internal/v1";
    private static final String WORKSPACES = BASE_PATH + "/workspaces/{workspaceId}";
    private static final String GIT_STATUS = WORKSPACES + "/repositories/{repositoryId}/git/status";
    private static final String GIT_DIFF = WORKSPACES + "/repositories/{repositoryId}/git/diff";
    private static final String GIT_COMMIT = WORKSPACES + "/repositories/{repositoryId}/git/commit";
    private static final String TEST_SNAPSHOT = WORKSPACES
            + "/repositories/{repositoryId}/test-snapshots/{snapshotWorkspaceId}";
    private static final String SANDBOXES = BASE_PATH + "/sandboxes";
    private static final String SANDBOX = BASE_PATH + "/sandboxes/{sandboxId}";
    private static final String SANDBOX_LEASE_RENEW = SANDBOX + "/lease/renew";
    private static final String TOOL_EXECUTIONS = BASE_PATH + "/sandboxes/{sandboxId}/tool-executions";
    private static final String TOOL_EXECUTION = BASE_PATH + "/tool-executions/{executionId}";
    private static final String TOOL_EXECUTION_LOGS = TOOL_EXECUTION + "/logs";
    private static final String GIT_STORE_SYNC = BASE_PATH + "/git-stores/{repositoryId}/sync";
    private static final String GIT_PUSH = WORKSPACES + "/repositories/{repositoryId}/git/push";
    private static final String TEST_EXECUTIONS = BASE_PATH + "/test-executions";
    private static final String MERGE_PREVIEWS = BASE_PATH + "/merge-previews";
    private static final String GIT_RESOLUTIONS = BASE_PATH + "/git-resolutions";

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public SandboxWorkerClient(RestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等准备一个多仓库 Workspace，返回真实 HEAD 与 storageKey。
     */
    public WorkerWorkspace provisionWorkspace(UUID workspaceId, WorkerWorkspaceProvisionRequest request) {
        return execute(() -> client.put()
                .uri(WORKSPACES, workspaceId)
                .body(request)
                .retrieve()
                .body(WorkerWorkspace.class));
    }

    /**
     * 查询 Workspace 与各仓库当前 HEAD。
     */
    public WorkerWorkspace getWorkspace(UUID workspaceId) {
        return execute(() -> client.get()
                .uri(WORKSPACES, workspaceId)
                .retrieve()
                .body(WorkerWorkspace.class));
    }

    /**
     * 移除 Workspace 独立仓库，不删除共享 Git Store。
     */
    public void deleteWorkspace(UUID workspaceId) {
        execute(() -> {
            client.delete().uri(WORKSPACES, workspaceId).retrieve().toBodilessEntity();
            return null;
        });
    }

    /**
     * 查询仓库当前分支、HEAD 与结构化变更。
     */
    public WorkerGitStatus getWorkspaceGitStatus(UUID workspaceId, UUID repositoryId) {
        return execute(() -> client.get()
                .uri(GIT_STATUS, workspaceId, repositoryId)
                .retrieve()
                .body(WorkerGitStatus.class));
    }

    /**
     * 同步受控 Git Store
     */
    public WorkerGitStoreSyncResponse syncGitStore(UUID repositoryId, WorkerGitStoreSyncRequest request) {
        try {
            return execute(() -> client.post()
                    .uri(GIT_STORE_SYNC, repositoryId)
                    .body(request)
                    .retrieve()
                    .body(WorkerGitStoreSyncResponse.class));
        } catch (ApiException failure) {
            log.error("sandbox git store sync failed repositoryId={} code={} message={}", repositoryId,
                    failure.code(), failure.getMessage(), failure);
            throw failure;
        }
    }

    /**
     * 生成包含未跟踪文件的完整 Diff。
     * <p>
     * Worker 端点声明了 {@code @RequestBody}（暂无参数）；无 Content-Type 的 POST 会被
     * Spring 按 octet-stream 处理并返回 415，因此这里显式发送空 JSON。
     */
    public WorkerGitDiff createWorkspaceGitDiff(UUID workspaceId, UUID repositoryId) {
        return execute(() -> client.post()
                .uri(GIT_DIFF, workspaceId, repositoryId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of())
                .retrieve()
                .body(WorkerGitDiff.class));
    }

    /**
     * Commits the exact Worker snapshot identified by its head and patch hash.
     */
    public WorkerGitCommitResponse commitWorkspaceDiff(UUID workspaceId, UUID repositoryId,
                                                       WorkerGitCommitRequest request) {
        try {
            return execute(() -> client.post()
                    .uri(GIT_COMMIT, workspaceId, repositoryId)
                    .body(request)
                    .retrieve()
                    .body(WorkerGitCommitResponse.class));
        } catch (ApiException failure) {
            log.error("sandbox git commit failed workspaceId={} repositoryId={} code={} message={}", workspaceId,
                    repositoryId, failure.code(), failure.getMessage(), failure);
            throw failure;
        }
    }

    /**
     * 校验 expectedHeadCommit 并带凭证发起推送。
     */
    public WorkerGitPushResponse pushWorkspaceBranch(UUID workspaceId, UUID repositoryId, WorkerGitPushRequest request) {
        try {
            return execute(() -> client.post()
                    .uri(GIT_PUSH, workspaceId, repositoryId)
                    .body(request)
                    .retrieve()
                    .body(WorkerGitPushResponse.class));
        } catch (ApiException failure) {
            log.error("sandbox git push failed workspaceId={} repositoryId={} code={} message={}", workspaceId,
                    repositoryId, failure.code(), failure.getMessage(), failure);
            throw failure;
        }
    }

    /**
     * 创建 Sandbox 并应用 Worker 本地资源上限。
     */
    public WorkerSandbox createSandbox(WorkerCreateSandboxRequest request) {
        return execute(() -> client.post()
                .uri(SANDBOXES)
                .body(request)
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 查询 Sandbox 当前状态与租约期限。
     */
    public WorkerSandbox getSandbox(UUID sandboxId) {
        return execute(() -> client.get()
                .uri(SANDBOX, sandboxId)
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 延长 Sandbox 空闲租约；ttlSeconds 为空时使用 Worker 本地默认配置。
     */
    public WorkerSandbox renewSandbox(UUID sandboxId, Long ttlSeconds) {
        return execute(() -> client.post()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(SANDBOX_LEASE_RENEW);
                    if (ttlSeconds != null) {
                        builder.queryParam("ttlSeconds", ttlSeconds);
                    }
                    return builder.build(sandboxId);
                })
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 延长 Sandbox 空闲租约，使用 Worker 本地默认配置。
     */
    public WorkerSandbox renewSandbox(UUID sandboxId) {
        return renewSandbox(sandboxId, null);
    }

    /**
     * 取消活跃工具执行并销毁临时容器；持久 Workspace 不随 Sandbox 删除。
     */
    public void destroySandbox(UUID sandboxId) {
        execute(() -> {
            client.delete().uri(SANDBOX, sandboxId).retrieve().toBodilessEntity();
            return null;
        });
    }

    /**
     * 提交结构化工具执行，返回 202 入队结果。
     */
    public WorkerToolExecution submitToolExecution(UUID sandboxId, WorkerToolExecutionRequest request) {
        return execute(() -> client.post()
                .uri(TOOL_EXECUTIONS, sandboxId)
                .body(request)
                .retrieve()
                .body(WorkerToolExecution.class));
    }

    /**
     * 查询持久化工具执行记录与终态结果。
     */
    public WorkerToolExecution getToolExecution(UUID executionId) {
        return execute(() -> client.get()
                .uri(TOOL_EXECUTION, executionId)
                .retrieve()
                .body(WorkerToolExecution.class));
    }

    /**
     * 按递增游标查询工具执行日志。
     */
    public WorkerExecutionLogs getToolExecutionLogs(UUID executionId, long after, int limit) {
        return execute(() -> client.get()
                .uri(uri -> uri.path(TOOL_EXECUTION_LOGS)
                        .queryParam("after", after)
                        .queryParam("limit", limit)
                        .build(executionId))
                .retrieve()
                .body(WorkerExecutionLogs.class));
    }

    /**
     * 在 Worker 内固化当前未提交工作树，不读取或传输宿主机路径。
     */
    public WorkerWorkspace createTestSnapshot(UUID workspaceId, UUID repositoryId,
                                              UUID snapshotWorkspaceId, UUID projectId,
                                              String expectedHeadCommit) {
        return execute(() -> client.post()
                .uri(uri -> uri.path(TEST_SNAPSHOT).queryParam("projectId", projectId)
                        .queryParam("expectedHeadCommit", expectedHeadCommit)
                        .build(workspaceId, repositoryId, snapshotWorkspaceId))
                .retrieve().body(WorkerWorkspace.class));
    }

    /**
     * 同步执行已由主后端校验的 Testset 定义。
     */
    public WorkerTestExecutionResponse executeTests(WorkerTestExecutionRequest request) {
        return execute(() -> client.post().uri(TEST_EXECUTIONS).body(request).retrieve()
                .body(WorkerTestExecutionResponse.class));
    }

    /**
     * 解析真实 Git 引用并执行只读合并预演。
     */
    public WorkerMergePreviewResponse mergePreview(WorkerMergePreviewRequest request) {
        return execute(() -> client.post().uri(MERGE_PREVIEWS).body(request).retrieve()
                .body(WorkerMergePreviewResponse.class));
    }

    /**
     * 在受控 Worker 中把 Git 引用解析为固定 commit SHA。
     */
    public WorkerGitResolveResponse resolveGitRef(WorkerGitResolveRequest request) {
        return execute(() -> client.post().uri(GIT_RESOLUTIONS).body(request).retrieve()
                .body(WorkerGitResolveResponse.class));
    }

    /**
     * 统一执行调用并做错误映射，不让 RestClient 原始异常泄漏到上层。
     */
    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException exception) {
            throw workerError(exception);
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE",
                    "Sandbox worker is unavailable: " + exception.getMessage());
        }
    }

    /**
     * 把 Worker 非 2xx 响应映射为携带业务错误码的 ApiException；解析失败时退回通用错误。
     */
    private ApiException workerError(RestClientResponseException exception) {
        String code = "SANDBOX_WORKER_ERROR";
        String message = exception.getMessage();
        try {
            String body = exception.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                WorkerErrorResponse error = objectMapper.readValue(body, WorkerErrorResponse.class);
                if (error != null) {
                    if (error.getCode() != null && !error.getCode().isBlank()) {
                        code = error.getCode();
                    }
                    if (error.getMessage() != null && !error.getMessage().isBlank()) {
                        message = error.getMessage();
                    }
                }
            }
        } catch (Exception ignored) {
            // 错误体非预期结构时退回通用错误码与原始消息。
        }
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, code, message);
    }
}
