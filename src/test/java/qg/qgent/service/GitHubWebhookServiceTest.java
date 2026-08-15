package qg.qgent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubWebhookProperties;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.GitHubWebhookDeliveryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubRepositoryDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.GitHubWebhookDeliveryMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GitHubWebhookServiceTest {
    private static final String SECRET = "test-webhook-secret";

    private final GitHubWebhookProperties properties = new GitHubWebhookProperties();
    private final GitHubWebhookDeliveryMapper deliveryMapper = mock(GitHubWebhookDeliveryMapper.class);
    private final GitHubInstallationMapper installationMapper = mock(GitHubInstallationMapper.class);
    private final GitHubRepositoryMapper repositoryMapper = mock(GitHubRepositoryMapper.class);
    private final ProjectRepositoryMapper projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final EventService eventService = mock(EventService.class);
    private final GitHubAppClient gitHubClient = mock(GitHubAppClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private GitHubWebhookService service;

    @BeforeEach
    void setUp() {
        properties.setSecret(SECRET);
        properties.setMaxBodyBytes(1024 * 1024);
        initTableInfo();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new GitHubWebhookService(properties, deliveryMapper, installationMapper, repositoryMapper,
                projectRepositoryMapper, mergeRequestMapper, eventService, gitHubClient,
                objectMapper, transactionManager);
    }

    /**
     * 初始化 MyBatis-Plus 实体元数据；纯 Mockito 环境无 Spring 容器，lambda 查询需要 TableInfo。
     */
    private static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "GitHubWebhookServiceTest");
        TableInfoHelper.initTableInfo(assistant, GitHubWebhookDeliveryEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubInstallationEntity.class);
        TableInfoHelper.initTableInfo(assistant, GitHubRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProjectRepositoryEntity.class);
        TableInfoHelper.initTableInfo(assistant, MergeRequestEntity.class);
    }

    // ---------- 验签 ----------

    @Test
    void acceptsValidSignature() {
        String body = json("{\"zen\":\"hello\",\"hook_id\":1}");
        String signature = sign(SECRET, body);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(body.getBytes(StandardCharsets.UTF_8), signature, "ping", delivery("d1"));

        verify(deliveryMapper).insert(any(GitHubWebhookDeliveryEntity.class));
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "PROCESSED".equals(row.getStatus())));
    }

    @Test
    void rejectsMissingSignature() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.handle(bodyBytes("{\"zen\":\"x\"}"), null, "ping", delivery("d1")));
        assertEquals(401, failure.status().value());
        verify(deliveryMapper, never()).insert(any(GitHubWebhookDeliveryEntity.class));
    }

    @Test
    void rejectsWrongSignature() {
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), sign("other-secret", "{\"zen\":\"x\"}"),
                        "ping", delivery("d1")));
        assertEquals(401, failure.status().value());
        verify(deliveryMapper, never()).insert(any(GitHubWebhookDeliveryEntity.class));
    }

    @Test
    void rejectsBadPrefixAndDigestLength() {
        ApiException prefix = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), "md5=abc", "ping", delivery("d1")));
        assertEquals(401, prefix.status().value());

        ApiException length = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), "sha256=abc", "ping", delivery("d1")));
        assertEquals(401, length.status().value());
        verify(deliveryMapper, never()).insert(any(GitHubWebhookDeliveryEntity.class));
    }

    @Test
    void failsClosedWhenSecretMissing() {
        properties.setSecret("");
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), sign(SECRET, "{\"zen\":\"x\"}"), "ping", delivery("d1")));
        assertEquals(503, failure.status().value());
        verify(deliveryMapper, never()).insert(any(GitHubWebhookDeliveryEntity.class));
    }

    @Test
    void rejectsTamperedBody() {
        String body = "{\"zen\":\"original\"}";
        String signature = sign(SECRET, body);
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"tampered\"}"), signature, "ping", delivery("d1")));
        assertEquals(401, failure.status().value());
    }

    @Test
    void rejectsOversizedBody() {
        properties.setMaxBodyBytes(10);
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), sign(SECRET, "{\"zen\":\"x\"}"), "ping", delivery("d1")));
        assertEquals(413, failure.status().value());
    }

    @Test
    void rejectsMissingHeaders() {
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes("{\"zen\":\"x\"}"), sign(SECRET, "{\"zen\":\"x\"}"), " ", delivery("d1")));
        assertEquals(400, failure.status().value());
    }

    // ---------- 幂等状态机 ----------

    @Test
    void duplicateDeliveryProcessedIsIdempotent() {
        String body = "{\"zen\":\"x\"}";
        GitHubWebhookDeliveryEntity processed = deliveryRow("d1", "PROCESSED");
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(processed);

        service.handle(bodyBytes(body), sign(SECRET, body), "ping", "d1");

        verify(deliveryMapper, never()).insert(any(GitHubWebhookDeliveryEntity.class));
        verify(deliveryMapper, never()).updateById(any(GitHubWebhookDeliveryEntity.class));
    }

    @Test
    void failedDeliveryCanRetryAfterRevalidation() {
        String body = "{\"zen\":\"x\"}";
        GitHubWebhookDeliveryEntity failed = deliveryRow("d1", "FAILED");
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(failed);

        service.handle(bodyBytes(body), sign(SECRET, body), "ping", "d1");

        // FAILED 重置为 RECEIVED 后重新处理，处理成功最终落 PROCESSED，attempt_count 累加
        assertEquals(2, failed.getAttemptCount());
        assertEquals("PROCESSED", failed.getStatus());
        verify(deliveryMapper, times(2)).updateById(failed);
    }

    @Test
    void receivedDeliveryReturnsServiceUnavailable() {
        String body = "{\"zen\":\"x\"}";
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1"))
                .thenReturn(deliveryRow("d1", "RECEIVED"));

        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes(body), sign(SECRET, body), "ping", "d1"));
        assertEquals(503, failure.status().value());
    }

    @Test
    void staleReceivedDeliveryIsReclaimedAndProcessed() {
        String body = "{\"zen\":\"x\"}";
        GitHubWebhookDeliveryEntity stale = deliveryRow("d1", "RECEIVED");
        // 超过 5 分钟阈值：视为处理中断，允许重新领取
        stale.setReceivedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(10));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(stale);

        service.handle(bodyBytes(body), sign(SECRET, body), "ping", "d1");

        assertEquals(2, stale.getAttemptCount());
        assertEquals("PROCESSED", stale.getStatus());
        verify(deliveryMapper, times(2)).updateById(stale);
    }

    @Test
    void unknownEventIsIgnored() {
        String body = "{\"action\":\"created\"}";
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(bodyBytes(body), sign(SECRET, body), "check_run", delivery("d1"));

        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "IGNORED".equals(row.getStatus())));
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
    }

    // ---------- installation ----------

    @Test
    void installationCreatedActivatesAndPublishesOnlyToBoundProjects() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        ProjectEntity project = new ProjectEntity();
        project.setId(UUID.randomUUID());
        project.setTeamId(installation.getTeamId());
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(installation.getId());
        mirror.setProviderRepositoryId(500L);
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(project.getId());
        binding.setRepositoryId(mirror.getId());
        when(installationMapper.selectOne(any())).thenReturn(installation);
        // 该安装下有一个仓库，且仓库只绑定到 project：SSE 只发 project，不按 Team 广播
        when(repositoryMapper.selectList(any())).thenReturn(List.of(mirror));
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(bodyBytes("{\"action\":\"created\",\"installation\":{\"id\":100}}"),
                sign(SECRET, "{\"action\":\"created\",\"installation\":{\"id\":100}}"),
                "installation", delivery("d1"));

        assertEquals("ACTIVE", installation.getStatus());
        verify(installationMapper).updateById(installation);
        verify(eventService).publish(eq(project.getId()), isNull(), eq("github-installation.updated"),
                eq(installation.getId().toString()),
                argThat(p -> "ACTIVE".equals(p.get("status"))
                        && installation.getId().toString().equals(p.get("installationId"))));
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "PROCESSED".equals(row.getStatus())));
    }

    @Test
    void installationWithoutRepositoryBindingPublishesNoSse() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        when(installationMapper.selectOne(any())).thenReturn(installation);
        // 该安装下没有任何仓库：没有项目归属，不发布 SSE
        when(repositoryMapper.selectList(any())).thenReturn(List.of());
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(bodyBytes("{\"action\":\"created\",\"installation\":{\"id\":100}}"),
                sign(SECRET, "{\"action\":\"created\",\"installation\":{\"id\":100}}"),
                "installation", delivery("d1"));

        verify(installationMapper).updateById(installation);
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "PROCESSED".equals(row.getStatus())));
    }

    @Test
    void installationSuspendAndDeleteMapStates() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        when(installationMapper.selectOne(any())).thenReturn(installation);
        when(repositoryMapper.selectList(any())).thenReturn(List.of());
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(bodyBytes("{\"action\":\"suspend\",\"installation\":{\"id\":100}}"),
                sign(SECRET, "{\"action\":\"suspend\",\"installation\":{\"id\":100}}"),
                "installation", delivery("d2"));
        assertEquals("SUSPENDED", installation.getStatus());

        service.handle(bodyBytes("{\"action\":\"deleted\",\"installation\":{\"id\":100}}"),
                sign(SECRET, "{\"action\":\"deleted\",\"installation\":{\"id\":100}}"),
                "installation", delivery("d3"));
        assertEquals("DELETED", installation.getStatus());
    }

    @Test
    void installationUnknownIsIgnored() {
        when(installationMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(bodyBytes("{\"action\":\"created\",\"installation\":{\"id\":999}}"),
                sign(SECRET, "{\"action\":\"created\",\"installation\":{\"id\":999}}"),
                "installation", delivery("d1"));

        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "IGNORED".equals(row.getStatus())));
        verify(installationMapper, never()).updateById(any(GitHubInstallationEntity.class));
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
    }

    // ---------- installation_repositories ----------

    @Test
    void repositoryAddedCreatesMirrorAndPublishesToBoundProjects() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        GitHubRepositoryDetails details = new GitHubRepositoryDetails(500L, "octocat", "Hello-World",
                "main", "PUBLIC", false);
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(installation.getId());
        mirror.setProviderRepositoryId(500L);
        mirror.setAuthorizationStatus("AUTHORIZED");
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(UUID.randomUUID());
        binding.setRepositoryId(mirror.getId());

        when(installationMapper.selectOne(any())).thenReturn(installation);
        when(repositoryMapper.selectOne(any())).thenReturn(null); // 本地无镜像
        when(repositoryMapper.selectList(any())).thenReturn(List.of()); // prefetch 时本地缺失
        when(gitHubClient.listRepositories(100L)).thenReturn(List.of(details));
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(
                bodyBytes("{\"action\":\"added\",\"installation\":{\"id\":100},\"repositories_added\":[{\"id\":500,\"name\":\"Hello-World\",\"full_name\":\"octocat/Hello-World\"}]}"),
                sign(SECRET, "{\"action\":\"added\",\"installation\":{\"id\":100},\"repositories_added\":[{\"id\":500,\"name\":\"Hello-World\",\"full_name\":\"octocat/Hello-World\"}]}"),
                "installation_repositories", delivery("d1"));

        verify(repositoryMapper).insert(argThat((GitHubRepositoryEntity m) -> "AUTHORIZED".equals(m.getAuthorizationStatus())
                && Long.valueOf(500L).equals(m.getProviderRepositoryId())));
        verify(eventService).publish(eq(binding.getProjectId()), isNull(), eq("github-repository.updated"),
                anyString(), argThat(p -> "AUTHORIZED".equals(p.get("authorizationStatus"))));
    }

    @Test
    void repositoryRemovedRevokesMirrorAndPublishes() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(installation.getId());
        mirror.setProviderRepositoryId(500L);
        mirror.setAuthorizationStatus("AUTHORIZED");
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(UUID.randomUUID());
        binding.setRepositoryId(mirror.getId());

        when(installationMapper.selectOne(any())).thenReturn(installation);
        when(repositoryMapper.selectOne(any())).thenReturn(mirror);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        service.handle(
                bodyBytes("{\"action\":\"removed\",\"installation\":{\"id\":100},\"repositories_removed\":[{\"id\":500}]}"),
                sign(SECRET, "{\"action\":\"removed\",\"installation\":{\"id\":100},\"repositories_removed\":[{\"id\":500}]}"),
                "installation_repositories", delivery("d1"));

        assertEquals("REVOKED", mirror.getAuthorizationStatus());
        verify(repositoryMapper).updateById(mirror);
        verify(eventService).publish(eq(binding.getProjectId()), isNull(), eq("github-repository.updated"),
                anyString(), argThat(p -> "REVOKED".equals(p.get("authorizationStatus"))));
    }

    @Test
    void repositoryUpdateRejectedWhenInstallationMismatch() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        // 本地镜像属于另一个安装：payload installation=100 与 mirror.installationId 不一致
        mirror.setInstallationId(UUID.randomUUID());
        mirror.setProviderRepositoryId(500L);
        mirror.setAuthorizationStatus("AUTHORIZED");

        when(installationMapper.selectOne(any())).thenReturn(installation);
        when(repositoryMapper.selectOne(any())).thenReturn(mirror);
        // claim 时查不到（新建 RECEIVED）；markFailed 时能查到该记录并标记 FAILED
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(null, deliveryRow("d1", "RECEIVED"));

        String body = "{\"action\":\"added\",\"installation\":{\"id\":100},\"repositories_added\":[{\"id\":500,\"name\":\"Hello-World\",\"full_name\":\"octocat/Hello-World\"}]}";
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes(body), sign(SECRET, body), "installation_repositories", delivery("d1")));

        assertEquals("WEBHOOK_INSTALLATION_MISMATCH", failure.code());
        verify(repositoryMapper, never()).updateById(any(GitHubRepositoryEntity.class));
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
        // 业务失败后 delivery 标记 FAILED，供 GitHub 重投
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "FAILED".equals(row.getStatus())));
    }

    @Test
    void repositoryRemovedRejectedWhenInstallationMismatch() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        GitHubRepositoryEntity mirror = new GitHubRepositoryEntity();
        mirror.setId(UUID.randomUUID());
        mirror.setInstallationId(UUID.randomUUID()); // 与 payload installation=100 不一致
        mirror.setProviderRepositoryId(500L);
        mirror.setAuthorizationStatus("AUTHORIZED");

        when(installationMapper.selectOne(any())).thenReturn(installation);
        when(repositoryMapper.selectOne(any())).thenReturn(mirror);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(null, deliveryRow("d1", "RECEIVED"));

        String body = "{\"action\":\"removed\",\"installation\":{\"id\":100},\"repositories_removed\":[{\"id\":500}]}";
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes(body), sign(SECRET, body), "installation_repositories", delivery("d1")));

        assertEquals("WEBHOOK_INSTALLATION_MISMATCH", failure.code());
        verify(repositoryMapper, never()).updateById(any(GitHubRepositoryEntity.class));
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "FAILED".equals(row.getStatus())));
    }

    @Test
    void repositoryPrefetchFailureMarksDeliveryFailed() {
        GitHubInstallationEntity installation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        when(installationMapper.selectOne(any())).thenReturn(installation);
        // 本地无镜像 -> 需要补齐；GitHub 客户端调用失败 -> 整单 FAILED，等待重投
        when(repositoryMapper.selectOne(any())).thenReturn(null);
        when(repositoryMapper.selectList(any())).thenReturn(List.of());
        when(gitHubClient.listRepositories(100L))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "GITHUB_API_UNAVAILABLE", "upstream"));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate("d1")).thenReturn(null, deliveryRow("d1", "RECEIVED"));

        String body = "{\"action\":\"added\",\"installation\":{\"id\":100},\"repositories_added\":[{\"id\":500,\"name\":\"Hello-World\",\"full_name\":\"octocat/Hello-World\"}]}";
        ApiException failure = assertThrows(ApiException.class, () ->
                service.handle(bodyBytes(body), sign(SECRET, body), "installation_repositories", delivery("d1")));

        assertEquals("WEBHOOK_REPOSITORY_FETCH_FAILED", failure.code());
        verify(repositoryMapper, never()).insert(any(GitHubRepositoryEntity.class));
        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "FAILED".equals(row.getStatus())));
    }

    // ---------- pull_request ----------

    @Test
    void pullRequestOpenedUpsertsMirrorForAllProjectBindings() {
        GitHubRepositoryEntity githubRepo = new GitHubRepositoryEntity();
        githubRepo.setId(UUID.randomUUID());
        githubRepo.setProviderRepositoryId(500L);
        mockMatchingInstallation(githubRepo);
        ProjectRepositoryEntity bindingA = new ProjectRepositoryEntity();
        bindingA.setId(UUID.randomUUID());
        bindingA.setProjectId(UUID.randomUUID());
        bindingA.setRepositoryId(githubRepo.getId());
        ProjectRepositoryEntity bindingB = new ProjectRepositoryEntity();
        bindingB.setId(UUID.randomUUID());
        bindingB.setProjectId(UUID.randomUUID());
        bindingB.setRepositoryId(githubRepo.getId());

        when(repositoryMapper.selectOne(any())).thenReturn(githubRepo);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(bindingA, bindingB));
        when(mergeRequestMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"opened\",\"installation\":{\"id\":100},\"repository\":{\"id\":500},\"pull_request\":{"
                + "\"number\":128,\"title\":\"Add login\",\"merged\":false,"
                + "\"head\":{\"sha\":\"0123456789abcdef0123456789abcdef01234567\",\"ref\":\"feat/login\"},"
                + "\"base\":{\"ref\":\"main\"},\"updated_at\":\"2026-08-15T03:00:00Z\"}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(mergeRequestMapper, times(2)).insert(argThat((MergeRequestEntity mr) -> "OPEN".equals(mr.getStatus())
                && Long.valueOf(128L).equals(mr.getProviderNumber())
                && "feat/login".equals(mr.getSourceBranch())
                && "main".equals(mr.getTargetBranch())
                && "0123456789abcdef0123456789abcdef01234567".equals(mr.getHeadCommit())));
        verify(eventService, times(2)).publish(any(), isNull(), eq("merge-request.updated"),
                anyString(), argThat(p -> "OPEN".equals(p.get("status"))
                        && Long.valueOf(128L).equals(p.get("number"))
                        && "0123456789abcdef0123456789abcdef01234567".equals(p.get("headCommit"))
                        && "2026-08-15T03:00:00Z".equals(p.get("providerUpdatedAt"))
                        && !p.containsKey("sequence")));
    }

    @Test
    void pullRequestClosedMergedMapsToMerged() {
        GitHubRepositoryEntity githubRepo = new GitHubRepositoryEntity();
        githubRepo.setId(UUID.randomUUID());
        githubRepo.setProviderRepositoryId(500L);
        mockMatchingInstallation(githubRepo);
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(UUID.randomUUID());
        binding.setRepositoryId(githubRepo.getId());
        when(repositoryMapper.selectOne(any())).thenReturn(githubRepo);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(mergeRequestMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"closed\",\"installation\":{\"id\":100},\"repository\":{\"id\":500},\"pull_request\":{"
                + "\"number\":128,\"merged\":true,"
                + "\"head\":{\"sha\":\"0123456789abcdef0123456789abcdef01234567\",\"ref\":\"feat/login\"},"
                + "\"base\":{\"ref\":\"main\"},\"updated_at\":\"2026-08-15T04:00:00Z\"}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(mergeRequestMapper).insert(argThat((MergeRequestEntity mr) -> "MERGED".equals(mr.getStatus())));
        verify(eventService).publish(any(), isNull(), eq("merge-request.updated"),
                anyString(), argThat(p -> "MERGED".equals(p.get("status"))));
    }

    @Test
    void pullRequestForUnboundRepositoryIsIgnored() {
        when(repositoryMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"opened\",\"installation\":{\"id\":100},\"repository\":{\"id\":999},\"pull_request\":{"
                + "\"number\":1,\"head\":{\"sha\":\"0123456789abcdef0123456789abcdef01234567\",\"ref\":\"f\"},"
                + "\"base\":{\"ref\":\"main\"}}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "IGNORED".equals(row.getStatus())));
        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
    }

    @Test
    void pullRequestInstallationMismatchIsIgnored() {
        GitHubRepositoryEntity githubRepo = new GitHubRepositoryEntity();
        githubRepo.setId(UUID.randomUUID());
        githubRepo.setProviderRepositoryId(500L);
        // payload installation=100，本地 mirror 属于另一个安装 -> 不一致，不落业务
        githubRepo.setInstallationId(UUID.randomUUID());
        when(repositoryMapper.selectOne(any())).thenReturn(githubRepo);
        when(installationMapper.selectOne(any())).thenReturn(null);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"opened\",\"installation\":{\"id\":100},\"repository\":{\"id\":500},\"pull_request\":{"
                + "\"number\":128,\"head\":{\"sha\":\"0123456789abcdef0123456789abcdef01234567\",\"ref\":\"feat/login\"},"
                + "\"base\":{\"ref\":\"main\"}}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "IGNORED".equals(row.getStatus())));
        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
        verify(eventService, never()).publish(any(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    void invalidHeadShaIsIgnored() {
        GitHubRepositoryEntity githubRepo = new GitHubRepositoryEntity();
        githubRepo.setId(UUID.randomUUID());
        githubRepo.setProviderRepositoryId(500L);
        mockMatchingInstallation(githubRepo);
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(UUID.randomUUID());
        binding.setRepositoryId(githubRepo.getId());
        when(repositoryMapper.selectOne(any())).thenReturn(githubRepo);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"opened\",\"installation\":{\"id\":100},\"repository\":{\"id\":500},\"pull_request\":{"
                + "\"number\":1,\"head\":{\"sha\":\"short\",\"ref\":\"f\"},\"base\":{\"ref\":\"main\"}}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(deliveryMapper).updateById(argThat((GitHubWebhookDeliveryEntity row) -> "IGNORED".equals(row.getStatus())));
        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
    }

    @Test
    void existingOpenMirrorIsUpdatedNotDuplicated() {
        GitHubRepositoryEntity githubRepo = new GitHubRepositoryEntity();
        githubRepo.setId(UUID.randomUUID());
        githubRepo.setProviderRepositoryId(500L);
        mockMatchingInstallation(githubRepo);
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(UUID.randomUUID());
        binding.setRepositoryId(githubRepo.getId());
        MergeRequestEntity existing = new MergeRequestEntity();
        existing.setId(UUID.randomUUID());
        existing.setProjectRepositoryId(binding.getId());
        existing.setProvider("GITHUB");
        existing.setProviderNumber(128L);
        existing.setStatus("OPEN");

        when(repositoryMapper.selectOne(any())).thenReturn(githubRepo);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(binding));
        when(mergeRequestMapper.selectOne(any())).thenReturn(existing);
        when(deliveryMapper.selectByProviderDeliveryIdForUpdate(anyString())).thenReturn(null);

        String body = "{\"action\":\"synchronize\",\"installation\":{\"id\":100},\"repository\":{\"id\":500},\"pull_request\":{"
                + "\"number\":128,\"title\":\"New title\",\"merged\":false,"
                + "\"head\":{\"sha\":\"abcdef0123456789abcdef0123456789abcdef01\",\"ref\":\"feat/login\"},"
                + "\"base\":{\"ref\":\"main\"},\"updated_at\":\"2026-08-15T05:00:00Z\"}}";
        service.handle(bodyBytes(body), sign(SECRET, body), "pull_request", delivery("d1"));

        verify(mergeRequestMapper, never()).insert(any(MergeRequestEntity.class));
        verify(mergeRequestMapper).updateById(existing);
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", existing.getHeadCommit());
        assertEquals("New title", existing.getTitle());
        assertEquals("OPEN", existing.getStatus());
    }

    // ---------- helpers ----------

    /**
     * 让 payload installation=100 与本地仓库镜像的 installationId 匹配（pull_request 校验用）。
     */
    private void mockMatchingInstallation(GitHubRepositoryEntity githubRepo) {
        GitHubInstallationEntity payloadInstallation = installationEntity(100L, UUID.randomUUID(), "ACTIVE");
        githubRepo.setInstallationId(payloadInstallation.getId());
        when(installationMapper.selectOne(any())).thenReturn(payloadInstallation);
    }

    private byte[] bodyBytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private String json(String body) {
        return body;
    }

    private String delivery(String id) {
        return id;
    }

    private String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private GitHubWebhookDeliveryEntity deliveryRow(String deliveryId, String status) {
        GitHubWebhookDeliveryEntity row = new GitHubWebhookDeliveryEntity();
        row.setId(UUID.randomUUID());
        row.setProviderDeliveryId(deliveryId);
        row.setStatus(status);
        row.setAttemptCount(1);
        return row;
    }

    private GitHubInstallationEntity installationEntity(long providerInstallationId, UUID teamId, String status) {
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setTeamId(teamId);
        installation.setProviderInstallationId(providerInstallationId);
        installation.setStatus(status);
        return installation;
    }
}
