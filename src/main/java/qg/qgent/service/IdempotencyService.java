package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.api.PersistedApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.IdempotencyRecordEntity;
import qg.qgent.mapper.IdempotencyRecordMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 写请求先提交短期占位，再把业务变更和安全响应放在同一事务中提交。
 * ApiException 只缓存 status/code/message（details 固定为空）；未知异常删除占位并允许重试。
 */
@Service
public class IdempotencyService {
    private static final Duration PENDING_LEASE = Duration.ofSeconds(30);
    private static final long INITIAL_POLL_MILLIS = 20L;
    private static final long MAX_POLL_MILLIS = 400L;
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private final IdempotencyRecordMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;

    public IdempotencyService(IdempotencyRecordMapper mapper, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * 执行幂等操作
     */
    public <T> T execute(UUID actorUserId, String scope, String key, Object request, int responseStatus,
            Class<T> responseType, Supplier<T> action) {
        validateKey(key);
        byte[] fingerprint = sha256(uuidBytes(actorUserId));
        byte[] requestHash = sha256(canonicalJson(request).getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        long backoff = INITIAL_POLL_MILLIS;

        while (true) {
            Claim<T> claim;
            try {
                claim = requiresNew.execute(status -> claim(actorUserId, fingerprint, scope, key, requestHash,
                        responseType));
            } catch (DuplicateKeyException e) {
                backoff = pause(backoff, deadline);
                continue;
            }
            if (claim == null) {
                throw new IllegalStateException("无法创建幂等占位记录");
            }
            if (claim.response != null) {
                return claim.response;
            }
            if (!claim.owner) {
                backoff = pause(backoff, deadline);
                continue;
            }

            try {
                ActionOutcome<T> outcome = required.execute(status -> {
                    try {
                        T result = action.get();
                        String safeResponse = safeResponseJson(result);
                        IdempotencyRecordEntity record = mapper.selectById(claim.recordId);
                        if (record == null || !MessageDigest.isEqual(record.getRequestHash(), requestHash)) {
                            throw new IllegalStateException("幂等占位记录丢失");
                        }
                        if (mapper.completeClaim(claim.recordId, requestHash, responseStatus, safeResponse) != 1) {
                            throw new IllegalStateException("幂等 lease 已被其他请求接管");
                        }
                        return ActionOutcome.success(result);
                    } catch (PersistedApiException e) {
                        if (!cacheError(claim.recordId, requestHash, e)) {
                            // 提交型错误对应的业务状态必须与错误缓存同事务提交，失去 lease 时全部回滚。
                            throw new IllegalStateException("幂等 lease 已被其他请求接管");
                        }
                        return ActionOutcome.failure(e);
                    }
                });
                if (outcome == null) {
                    throw new IllegalStateException("幂等事务没有返回结果");
                }
                if (outcome.error != null) {
                    throw outcome.error;
                }
                return outcome.result;
            } catch (ApiException e) {
                if (!(e instanceof PersistedApiException)) {
                    Boolean cached = requiresNew.execute(status -> cacheError(claim.recordId, requestHash, e));
                    if (!Boolean.TRUE.equals(cached)) {
                        return awaitCurrentResult(fingerprint, scope, key, requestHash, responseType);
                    }
                }
                throw e;
            } catch (RuntimeException e) {
                requiresNew.executeWithoutResult(status -> cleanupFailedClaim(claim.recordId, requestHash));
                throw e;
            }
        }
    }

    // 
    private <T> Claim<T> claim(UUID actorUserId, byte[] fingerprint, String scope, String key, byte[] requestHash,
            Class<T> responseType) {
        LocalDateTime now = now();
        IdempotencyRecordEntity existing = mapper.selectOne(Wrappers.<IdempotencyRecordEntity>lambdaQuery()
                .eq(IdempotencyRecordEntity::getActorFingerprint, fingerprint)
                .eq(IdempotencyRecordEntity::getScope, scope)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, key));
        if (existing != null && existing.getResponseBodyRedacted() != null && !existing.getExpiresAt().isAfter(now)) {
            mapper.deleteById(existing.getId());
            existing = null;
        }
        if (existing != null && !MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key 已用于不同请求");
        }
        if (existing != null && stalePending(existing, now)) {
            int deleted = mapper.deleteStaleClaim(existing.getId(), existing.getRequestHash(),
                    now.minus(PENDING_LEASE));
            if (deleted == 1) {
                existing = null;
            } else {
                return Claim.waiting();
            }
        }
        if (existing != null) {
            if (existing.getResponseBodyRedacted() == null) {
                return Claim.waiting();
            }
            if (existing.getResponseStatus() != null && existing.getResponseStatus() >= 400) {
                CachedError error = read(existing.getResponseBodyRedacted(), CachedError.class);
                throw new ApiException(HttpStatus.valueOf(existing.getResponseStatus()), error.getCode(),
                        error.getMessage());
            }
            return Claim.ready(read(existing.getResponseBodyRedacted(), responseType));
        }

        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setId(UuidV7.next());
        record.setActorUserId(actorUserId);
        record.setActorFingerprint(fingerprint);
        record.setScope(scope);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setCreatedAt(now);
        record.setExpiresAt(now.plusHours(24));
        mapper.insert(record);
        return Claim.owned(record.getId());
    }

    private boolean stalePending(IdempotencyRecordEntity record, LocalDateTime now) {
        return record.getResponseBodyRedacted() == null && record.getCreatedAt() != null
                && record.getCreatedAt().plus(PENDING_LEASE).isBefore(now);
    }

    private void cleanupFailedClaim(UUID recordId, byte[] requestHash) {
        mapper.deletePendingClaim(recordId, requestHash);
    }

    private boolean cacheError(UUID recordId, byte[] requestHash, ApiException error) {
        String response = safeResponseJson(new CachedError(error.code(), error.getMessage()));
        return mapper.completeClaim(recordId, requestHash, error.status().value(), response) == 1;
    }

    private <T> T awaitCurrentResult(byte[] fingerprint, String scope, String key, byte[] requestHash,
            Class<T> responseType) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        long backoff = INITIAL_POLL_MILLIS;
        while (true) {
            IdempotencyRecordEntity current = requiresNew.execute(status -> mapper.selectOne(
                    Wrappers.<IdempotencyRecordEntity>lambdaQuery()
                            .eq(IdempotencyRecordEntity::getActorFingerprint, fingerprint)
                            .eq(IdempotencyRecordEntity::getScope, scope)
                            .eq(IdempotencyRecordEntity::getIdempotencyKey, key)));
            if (current == null) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_LEASE_LOST",
                        "请求处理权已变化，请使用相同 Idempotency-Key 重试");
            }
            if (!MessageDigest.isEqual(current.getRequestHash(), requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key 已用于不同请求");
            }
            if (current.getResponseBodyRedacted() == null) {
                backoff = pause(backoff, deadline);
                continue;
            }
            if (current.getResponseStatus() != null && current.getResponseStatus() >= 400) {
                CachedError error = read(current.getResponseBodyRedacted(), CachedError.class);
                throw new ApiException(HttpStatus.valueOf(current.getResponseStatus()), error.getCode(),
                        error.getMessage());
            }
            return read(current.getResponseBodyRedacted(), responseType);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "写操作需要有效的 Idempotency-Key");
        }
    }

    private String canonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("请求无法规范化", e);
        }
    }

    private String safeResponseJson(Object value) {
        JsonNode node = canonicalMapper.valueToTree(value);
        rejectSensitiveFields(node);
        try {
            return canonicalMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法缓存幂等响应", e);
        }
    }

    private void rejectSensitiveFields(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey().toLowerCase(Locale.ROOT);
                if (name.contains("token") || name.contains("password") || name.contains("secret")
                        || name.contains("privatekey")) {
                    throw new IllegalStateException("幂等响应包含不可缓存的敏感字段");
                }
                rejectSensitiveFields(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectSensitiveFields);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return canonicalMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取幂等响应", e);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
                .array();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private long pause(long delayMillis, long deadline) {
        if (System.nanoTime() >= deadline) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "相同请求仍在处理中，请稍后使用相同 Idempotency-Key 重试");
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_INTERRUPTED", "等待相同请求时被中断");
        }
        return Math.min(delayMillis * 2, MAX_POLL_MILLIS);
    }

    private static final class Claim<T> {
        private final UUID recordId;
        private final boolean owner;
        private final T response;

        private Claim(UUID recordId, boolean owner, T response) {
            this.recordId = recordId;
            this.owner = owner;
            this.response = response;
        }

        private static <T> Claim<T> owned(UUID recordId) {
            return new Claim<>(recordId, true, null);
        }

        private static <T> Claim<T> waiting() {
            return new Claim<>(null, false, null);
        }

        private static <T> Claim<T> ready(T response) {
            return new Claim<>(null, false, response);
        }
    }

    private static final class ActionOutcome<T> {
        private final T result;
        private final PersistedApiException error;

        private ActionOutcome(T result, PersistedApiException error) {
            this.result = result;
            this.error = error;
        }

        private static <T> ActionOutcome<T> success(T result) {
            return new ActionOutcome<>(result, null);
        }

        private static <T> ActionOutcome<T> failure(PersistedApiException error) {
            return new ActionOutcome<>(null, error);
        }
    }

    public static final class CachedError {
        private String code;
        private String message;

        public CachedError() {
        }

        private CachedError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
