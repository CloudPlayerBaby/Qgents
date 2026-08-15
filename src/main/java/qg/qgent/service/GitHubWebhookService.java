package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
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
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GitHub Webhook 接收与同步服务。
 * 接口不携带 Qgents JWT，安全依据为 X-Hub-Signature-256、X-GitHub-Event、X-GitHub-Delivery 和 Webhook Secret。
 * 采用同步处理：验签通过后按 delivery 幂等状态机领取，在本地事务内更新镜像与投递状态，
 * 并在同一事务内持久化 EventService 事件；事务内不调用 GitHub、Worker 或其他外部 HTTP。
 * 只记录 delivery id、事件名、处理结果和稳定失败码，不记录 Secret、完整 payload 或凭据。
 */
@Service
@Slf4j
public class GitHubWebhookService {
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String STATUS_RECEIVED = "RECEIVED";
    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_IGNORED = "IGNORED";
    private static final String STATUS_FAILED = "FAILED";
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-fA-F]{40,64}$");

    private final GitHubWebhookProperties properties;
    private final GitHubWebhookDeliveryMapper deliveryMapper;
    private final GitHubInstallationMapper installationMapper;
    private final GitHubRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final MergeRequestMapper mergeRequestMapper;
    private final EventService eventService;
    private final GitHubAppClient gitHubClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public GitHubWebhookService(GitHubWebhookProperties properties, GitHubWebhookDeliveryMapper deliveryMapper,
                                GitHubInstallationMapper installationMapper, GitHubRepositoryMapper repositoryMapper,
                                ProjectRepositoryMapper projectRepositoryMapper, ProjectMapper projectMapper,
                                MergeRequestMapper mergeRequestMapper, EventService eventService,
                                GitHubAppClient gitHubClient, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.deliveryMapper = deliveryMapper;
        this.installationMapper = installationMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.mergeRequestMapper = mergeRequestMapper;
        this.eventService = eventService;
        this.gitHubClient = gitHubClient;
        this.objectMapper = objectMapper;
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Webhook 入口：验签、幂等领取、业务同步。业务完成或幂等命中时返回，异常由全局处理器转 HTTP 状态。
     */
    public void handle(byte[] body, String signature, String eventName, String deliveryId) {
        // 1. Secret 未配置时 fail-closed，不得接受请求
        if (!properties.secretConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_SECRET_NOT_CONFIGURED",
                    "GitHub Webhook Secret 未配置，服务不可用");
        }
        if (body == null || body.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_BODY_REQUIRED", "Webhook 请求体不能为空");
        }
        if (body.length > properties.getMaxBodyBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_BODY_TOO_LARGE",
                    "Webhook 请求体超过大小上限");
        }
        requireHeaders(eventName, deliveryId);
        verifySignature(body, signature);

        JsonNode payload = parsePayload(body);
        String action = text(payload, "action");
        Long providerInstallationId = nestedLong(payload, "installation", "id");
        Long providerRepositoryId = nestedLong(payload, "repository", "id");
        String payloadSha256 = sha256Hex(body);

        // 短事务：领取/创建投递记录并提交，保证 FAILED 标记可持久化
        GitHubWebhookDeliveryEntity row = claim(deliveryId, eventName, action,
                providerInstallationId, providerRepositoryId, payloadSha256);
        if (isTerminal(row)) {
            return; // 幂等命中：PROCESSED/IGNORED 直接返回 200
        }

        // 事务外补齐仓库详情（仅在 installation_repositories added 且本地缺失时调用 GitHub）
        Map<Long, GitHubRepositoryDetails> fetched = prefetchRepositoryDetails(payload, eventName, action);

        try {
            required.execute(status -> {
                handleEvent(payload, eventName, action, row, fetched);
                return null;
            });
        } catch (RuntimeException failure) {
            markFailed(deliveryId, failure);
            throw failure;
        }
    }

    // ---------- 验签与请求校验 ----------

    private void requireHeaders(String eventName, String deliveryId) {
        if (eventName == null || eventName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_HEADER_INVALID", "缺少 X-GitHub-Event");
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_HEADER_INVALID", "X-GitHub-Delivery 缺失或格式不合法");
        }
    }

    /**
     * HMAC-SHA256 验签，使用常量时间比较和原始 body 字节；不先反序列化再重新序列化。
     */
    private void verifySignature(byte[] body, String signature) {
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "缺少或非法的 X-Hub-Signature-256");
        }
        String provided = signature.substring(SIGNATURE_PREFIX.length());
        if (provided.length() != 64 || !provided.matches("[0-9a-fA-F]{64}")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "X-Hub-Signature-256 摘要长度或格式不合法");
        }
        String expected = hmacSha256Hex(properties.getSecret(), body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                provided.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_MISMATCH", "Webhook 签名不匹配");
        }
    }

    private JsonNode parsePayload(byte[] body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_PAYLOAD_INVALID", "Webhook 请求体不是合法 JSON");
        }
    }

    // ---------- 投递幂等状态机 ----------

    /**
     * 短事务领取投递记录：
     * 不存在 -> 创建 RECEIVED；已 PROCESSED/IGNORED -> 幂等返回；FAILED -> 重置 RECEIVED 并累加 attempt；
     * 已 RECEIVED（并发处理中）-> 503 让 GitHub 重试。
     */
    private GitHubWebhookDeliveryEntity claim(String deliveryId, String eventName, String action,
                                              Long providerInstallationId, Long providerRepositoryId, String payloadSha256) {
        try {
            return required.execute(status -> claimInTransaction(deliveryId, eventName, action,
                    providerInstallationId, providerRepositoryId, payloadSha256));
        } catch (DuplicateKeyException race) {
            // 并发创建投递记录：唯一键冲突，重查一次最终状态
            return required.execute(status -> claimInTransaction(deliveryId, eventName, action,
                    providerInstallationId, providerRepositoryId, payloadSha256));
        }
    }

    private GitHubWebhookDeliveryEntity claimInTransaction(String deliveryId, String eventName, String action,
                                                           Long providerInstallationId, Long providerRepositoryId,
                                                           String payloadSha256) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        GitHubWebhookDeliveryEntity existing = deliveryMapper.selectByProviderDeliveryIdForUpdate(deliveryId);
        if (existing != null) {
            if (isTerminal(existing)) {
                return existing; // PROCESSED/IGNORED：幂等命中
            }
            if (STATUS_RECEIVED.equals(existing.getStatus())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_DELIVERY_IN_PROGRESS",
                        "该投递正在处理中，请稍后重试");
            }
            // FAILED：重新验签通过后重置为 RECEIVED 再次处理
            existing.setStatus(STATUS_RECEIVED);
            existing.setAttemptCount(existing.getAttemptCount() == null ? 1 : existing.getAttemptCount() + 1);
            existing.setFailureCode(null);
            existing.setReceivedAt(now);
            existing.setUpdatedAt(now);
            deliveryMapper.updateById(existing);
            return existing;
        }
        GitHubWebhookDeliveryEntity row = new GitHubWebhookDeliveryEntity();
        row.setId(UuidV7.next());
        row.setProviderDeliveryId(deliveryId);
        row.setEventName(eventName);
        row.setAction(action);
        row.setProviderInstallationId(providerInstallationId);
        row.setProviderRepositoryId(providerRepositoryId);
        row.setPayloadSha256(payloadSha256);
        row.setStatus(STATUS_RECEIVED);
        row.setAttemptCount(1);
        row.setReceivedAt(now);
        row.setUpdatedAt(now);
        deliveryMapper.insert(row);
        return row;
    }

    /**
     * 业务失败时以独立事务持久化 FAILED，便于 GitHub 重投时识别并重新处理。
     */
    private void markFailed(String deliveryId, RuntimeException failure) {
        try {
            requiresNew.execute(status -> {
                GitHubWebhookDeliveryEntity row = deliveryMapper.selectByProviderDeliveryIdForUpdate(deliveryId);
                if (row != null && STATUS_RECEIVED.equals(row.getStatus())) {
                    row.setStatus(STATUS_FAILED);
                    row.setFailureCode(failure instanceof ApiException api ? api.code() : "WEBHOOK_PROCESSING_FAILED");
                    row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    deliveryMapper.updateById(row);
                }
                return null;
            });
        } catch (RuntimeException ignored) {
            log.warn("github webhook delivery failed mark skipped, deliveryId={}", deliveryId);
        }
    }

    private boolean isTerminal(GitHubWebhookDeliveryEntity row) {
        return STATUS_PROCESSED.equals(row.getStatus()) || STATUS_IGNORED.equals(row.getStatus());
    }

    // ---------- 事件处理 ----------

    private void handleEvent(JsonNode payload, String eventName, String action,
                             GitHubWebhookDeliveryEntity row, Map<Long, GitHubRepositoryDetails> fetched) {
        switch (eventName) {
            case "ping" -> complete(row, STATUS_PROCESSED);
            case "installation" -> handleInstallation(payload, row);
            case "installation_repositories" -> handleInstallationRepositories(payload, action, row, fetched);
            case "pull_request" -> handlePullRequest(payload, row);
            default -> complete(row, STATUS_IGNORED); // 未知事件：白名单之外不落业务
        }
    }

    /**
     * installation：按 provider installation id 更新本地安装状态；只有能确定团队/项目归属时才发布 SSE。
     */
    private void handleInstallation(JsonNode payload, GitHubWebhookDeliveryEntity row) {
        Long providerInstallationId = nestedLong(payload, "installation", "id");
        String action = text(payload, "action");
        if (providerInstallationId == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        GitHubInstallationEntity installation = installationMapper.selectOne(Wrappers
                .<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getProviderInstallationId, providerInstallationId));
        if (installation == null) {
            // 本地无安装记录时不根据 payload 猜测 team
            complete(row, STATUS_IGNORED);
            return;
        }
        String status = switch (action == null ? "" : action) {
            case "created", "unsuspend", "new_permissions_accepted" -> "ACTIVE";
            case "suspend" -> "SUSPENDED";
            case "deleted" -> "DELETED";
            default -> null;
        };
        if (status == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        installation.setStatus(status);
        installation.setUpdatedAt(now);
        installationMapper.updateById(installation);

        // 只有能通过 team_id 找到项目绑定时才发布 SSE；没有项目归属时只记录投递状态
        List<ProjectEntity> projects = projectMapper.selectList(Wrappers.<ProjectEntity>lambdaQuery()
                .eq(ProjectEntity::getTeamId, installation.getTeamId()));
        for (ProjectEntity project : projects) {
            eventService.publish(project.getId(), null, "github-installation.updated",
                    installation.getId().toString(),
                    Map.of("installationId", installation.getId().toString(), "status", status));
        }
        complete(row, STATUS_PROCESSED);
    }

    /**
     * installation_repositories：added/removed 批量 upsert 仓库镜像授权状态，按受影响项目分别发布 SSE。
     */
    private void handleInstallationRepositories(JsonNode payload, String action,
                                                GitHubWebhookDeliveryEntity row,
                                                Map<Long, GitHubRepositoryDetails> fetched) {
        Long providerInstallationId = nestedLong(payload, "installation", "id");
        if (providerInstallationId == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        GitHubInstallationEntity installation = installationMapper.selectOne(Wrappers
                .<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getProviderInstallationId, providerInstallationId));
        if (installation == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("added".equals(action)) {
            for (JsonNode repo : nodes(payload, "repositories_added")) {
                upsertRepository(installation, repo, fetched, now);
            }
        } else if ("removed".equals(action)) {
            for (JsonNode repo : nodes(payload, "repositories_removed")) {
                revokeRepository(installation, repo, now);
            }
        } else {
            complete(row, STATUS_IGNORED);
            return;
        }
        complete(row, STATUS_PROCESSED);
    }

    private void upsertRepository(GitHubInstallationEntity installation, JsonNode repo,
                                  Map<Long, GitHubRepositoryDetails> fetched, LocalDateTime now) {
        long providerRepositoryId = repo.path("id").asLong(0);
        if (providerRepositoryId <= 0) {
            return;
        }
        GitHubRepositoryEntity mirror = repositoryMapper.selectOne(Wrappers.<GitHubRepositoryEntity>lambdaQuery()
                .eq(GitHubRepositoryEntity::getProviderRepositoryId, providerRepositoryId));
        GitHubRepositoryDetails details = fetched == null ? null : fetched.get(providerRepositoryId);
        boolean created = false;
        if (mirror == null) {
            if (details == null) {
                // 补齐失败：不把半成品标记为 AUTHORIZED，整单 FAILED 等待 GitHub 重投
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_REPOSITORY_DETAILS_MISSING",
                        "GitHub 仓库详情补齐失败，无法创建仓库镜像");
            }
            mirror = new GitHubRepositoryEntity();
            mirror.setId(UuidV7.next());
            mirror.setInstallationId(installation.getId());
            mirror.setProviderRepositoryId(details.getRepositoryId());
            mirror.setOwnerLogin(details.getOwnerLogin());
            mirror.setName(details.getName());
            mirror.setDefaultBranch(details.getDefaultBranch());
            mirror.setVisibility(details.getVisibility());
            mirror.setArchived(details.isArchived());
            mirror.setAuthorizationStatus("AUTHORIZED");
            mirror.setSyncedAt(now);
            repositoryMapper.insert(mirror);
            created = true;
        } else {
            mirror.setAuthorizationStatus("AUTHORIZED");
            mirror.setSyncedAt(now);
            if (details != null) {
                mirror.setOwnerLogin(details.getOwnerLogin());
                mirror.setName(details.getName());
                mirror.setDefaultBranch(details.getDefaultBranch());
                mirror.setVisibility(details.getVisibility());
                mirror.setArchived(details.isArchived());
            }
            repositoryMapper.updateById(mirror);
        }
        publishRepositoryUpdated(installation, mirror);
        log.info("github webhook repository authorized, providerRepositoryId={}, created={}", providerRepositoryId, created);
    }

    private void revokeRepository(GitHubInstallationEntity installation, JsonNode repo, LocalDateTime now) {
        long providerRepositoryId = repo.path("id").asLong(0);
        if (providerRepositoryId <= 0) {
            return;
        }
        GitHubRepositoryEntity mirror = repositoryMapper.selectOne(Wrappers.<GitHubRepositoryEntity>lambdaQuery()
                .eq(GitHubRepositoryEntity::getProviderRepositoryId, providerRepositoryId));
        if (mirror == null) {
            return; // 本地无镜像：无绑定可撤销
        }
        if (!"REVOKED".equals(mirror.getAuthorizationStatus())) {
            mirror.setAuthorizationStatus("REVOKED");
            mirror.setSyncedAt(now);
            repositoryMapper.updateById(mirror);
        }
        publishRepositoryUpdated(installation, mirror);
        log.info("github webhook repository revoked, providerRepositoryId={}", providerRepositoryId);
    }

    /**
     * 仓库授权状态事件只发送给绑定该仓库的项目；没有项目归属时不发布 SSE。
     */
    private void publishRepositoryUpdated(GitHubInstallationEntity installation, GitHubRepositoryEntity mirror) {
        List<ProjectRepositoryEntity> bindings = projectRepositoryMapper.selectList(Wrappers
                .<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getRepositoryId, mirror.getId()));
        for (ProjectRepositoryEntity binding : bindings) {
            eventService.publish(binding.getProjectId(), null, "github-repository.updated",
                    mirror.getId().toString(),
                    Map.of("installationId", installation.getId().toString(),
                            "repositoryId", mirror.getId().toString(),
                            "authorizationStatus", mirror.getAuthorizationStatus(),
                            "archived", Boolean.TRUE.equals(mirror.getArchived())));
        }
    }

    /**
     * pull_request：按 provider repository + PR number 向该仓库的全部项目绑定幂等更新 MR 镜像，
     * 每个成功更新的项目发布一次 merge-request.updated。
     */
    private void handlePullRequest(JsonNode payload, GitHubWebhookDeliveryEntity row) {
        Long providerRepositoryId = nestedLong(payload, "repository", "id");
        if (providerRepositoryId == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        GitHubRepositoryEntity githubRepository = repositoryMapper.selectOne(Wrappers
                .<GitHubRepositoryEntity>lambdaQuery()
                .eq(GitHubRepositoryEntity::getProviderRepositoryId, providerRepositoryId));
        if (githubRepository == null) {
            complete(row, STATUS_IGNORED); // 未找到本地仓库：不创建伪造数据
            return;
        }
        List<ProjectRepositoryEntity> bindings = projectRepositoryMapper.selectList(Wrappers
                .<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getRepositoryId, githubRepository.getId()));
        if (bindings.isEmpty()) {
            complete(row, STATUS_IGNORED); // 没有项目绑定：不创建伪造 MR
            return;
        }
        JsonNode pr = payload.path("pull_request");
        if (pr.isMissingNode() || pr.isNull()) {
            complete(row, STATUS_IGNORED);
            return;
        }
        long providerNumber = pr.path("number").asLong(0);
        if (providerNumber <= 0) {
            complete(row, STATUS_IGNORED);
            return;
        }
        String headSha = pr.path("head").path("sha").asText(null);
        if (headSha == null || !SHA_PATTERN.matcher(headSha).matches()) {
            complete(row, STATUS_IGNORED); // SHA 必须是 40~64 位十六进制
            return;
        }
        String sourceBranch = pr.path("head").path("ref").asText(null);
        String targetBranch = pr.path("base").path("ref").asText(null);
        if (sourceBranch == null || targetBranch == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        String action = text(payload, "action");
        boolean merged = pr.path("merged").asBoolean(false);
        String status = switch (action == null ? "" : action) {
            case "opened", "reopened", "synchronize" -> "OPEN";
            case "closed" -> merged ? "MERGED" : "CLOSED";
            default -> null;
        };
        if (status == null) {
            complete(row, STATUS_IGNORED);
            return;
        }
        String title = pr.path("title").asText(null);
        LocalDateTime providerUpdatedAt = parseIsoInstant(pr.path("updated_at").asText(null));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        for (ProjectRepositoryEntity binding : bindings) {
            MergeRequestEntity mr = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                    .eq(MergeRequestEntity::getProjectRepositoryId, binding.getId())
                    .eq(MergeRequestEntity::getProvider, "GITHUB")
                    .eq(MergeRequestEntity::getProviderNumber, providerNumber));
            boolean created = false;
            if (mr == null) {
                mr = new MergeRequestEntity();
                mr.setId(UuidV7.next());
                mr.setProjectRepositoryId(binding.getId());
                mr.setProvider("GITHUB");
                mr.setProviderNumber(providerNumber);
                mr.setSourceBranch(sourceBranch);
                mr.setTargetBranch(targetBranch);
                mr.setHeadCommit(headSha);
                mr.setTitle(title);
                mr.setStatus(status);
                mr.setQualityGateStatus("PENDING");
                mr.setProviderUpdatedAt(providerUpdatedAt);
                mr.setSyncedAt(now);
                mr.setCreatedAt(now);
                mergeRequestMapper.insert(mr);
                created = true;
            } else {
                // 更新镜像字段，不覆盖 task/workspace/author 等任务归属
                mr.setSourceBranch(sourceBranch);
                mr.setTargetBranch(targetBranch);
                mr.setHeadCommit(headSha);
                if (title != null) {
                    mr.setTitle(title);
                }
                // 已落库的 MERGED 终态不被旧同步请求覆盖回 OPEN
                if (!"MERGED".equals(mr.getStatus()) || merged) {
                    mr.setStatus(status);
                }
                mr.setProviderUpdatedAt(providerUpdatedAt);
                mr.setSyncedAt(now);
                mergeRequestMapper.updateById(mr);
            }
            publishMergeRequestUpdated(mr, binding);
            log.info("github webhook MR synced, providerNumber={}, status={}, projectRepositoryId={}, created={}",
                    providerNumber, status, binding.getId(), created);
        }
        complete(row, STATUS_PROCESSED);
    }

    /**
     * 发布 merge-request.updated 事件，payload 字段与 v1.7.1 契约保持一致（number 而非 providerNumber）。
     */
    private void publishMergeRequestUpdated(MergeRequestEntity mr, ProjectRepositoryEntity binding) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", binding.getProjectId().toString());
        payload.put("mergeRequestId", mr.getId().toString());
        payload.put("repositoryId", binding.getId().toString());
        payload.put("number", mr.getProviderNumber());
        payload.put("status", mr.getStatus());
        payload.put("headCommit", mr.getHeadCommit());
        payload.put("providerUpdatedAt", iso(mr.getProviderUpdatedAt()));
        payload.put("qualityGateStatus", mr.getQualityGateStatus());
        payload.put("timestamp", Instant.now().toString());
        eventService.publish(binding.getProjectId(), null, "merge-request.updated",
                mr.getId().toString(), payload);
    }

    private void complete(GitHubWebhookDeliveryEntity row, String status) {
        row.setStatus(status);
        row.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        deliveryMapper.updateById(row);
    }

    // ---------- 事务外补齐 ----------

    /**
     * installation_repositories added 且本地缺失的仓库，通过受控 GitHub 客户端补齐详情。
     * 仅在事务外调用外部 HTTP，不持有数据库事务或行锁。
     */
    private Map<Long, GitHubRepositoryDetails> prefetchRepositoryDetails(JsonNode payload, String eventName,
                                                                        String action) {
        if (!"installation_repositories".equals(eventName) || !"added".equals(action)) {
            return Map.of();
        }
        Long providerInstallationId = nestedLong(payload, "installation", "id");
        if (providerInstallationId == null) {
            return Map.of();
        }
        Set<Long> addedIds = new HashSet<>();
        for (JsonNode repo : nodes(payload, "repositories_added")) {
            long id = repo.path("id").asLong(0);
            if (id > 0) {
                addedIds.add(id);
            }
        }
        if (addedIds.isEmpty()) {
            return Map.of();
        }
        List<GitHubRepositoryEntity> local = repositoryMapper.selectList(Wrappers.<GitHubRepositoryEntity>lambdaQuery()
                .in(GitHubRepositoryEntity::getProviderRepositoryId, addedIds));
        Set<Long> localIds = new HashSet<>();
        for (GitHubRepositoryEntity mirror : local) {
            localIds.add(mirror.getProviderRepositoryId());
        }
        Set<Long> missing = new HashSet<>(addedIds);
        missing.removeAll(localIds);
        if (missing.isEmpty()) {
            return Map.of();
        }
        try {
            List<GitHubRepositoryDetails> all = gitHubClient.listRepositories(providerInstallationId);
            Map<Long, GitHubRepositoryDetails> result = new HashMap<>();
            for (GitHubRepositoryDetails details : all) {
                if (missing.contains(details.getRepositoryId())) {
                    result.put(details.getRepositoryId(), details);
                }
            }
            return result;
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_REPOSITORY_FETCH_FAILED",
                    "通过 GitHub 客户端补齐仓库详情失败");
        }
    }

    // ---------- 工具 ----------

    private String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Long nestedLong(JsonNode node, String parent, String field) {
        JsonNode value = node.path(parent).path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private List<JsonNode> nodes(JsonNode node, String field) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            array.forEach(result::add);
        }
        return result;
    }

    private LocalDateTime parseIsoInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }
}
