package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.IdempotencyRecordEntity;
import qg.qgent.mapper.IdempotencyRecordMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 写接口幂等支持（契约 §2）。
 * <p>
 * 采用“预占 + 事务”模式：先插入幂等预占记录，唯一键命中说明此前已提交过，
 * 通过 {@code SELECT ... FOR UPDATE} 等待并发请求完成后回放首次响应；相同键但请求体不同返回
 * {@code 409 IDEMPOTENCY_KEY_REUSED}。预占成功后执行业务并在同一事务内写入脱敏响应体，业务失败则整事务回滚。
 */
@Service
public class IdempotencyService {
    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordMapper idempotencyMapper;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyRecordMapper idempotencyMapper, ObjectMapper mapper) {
        this.idempotencyMapper = idempotencyMapper;
        this.mapper = mapper;
    }

    /**
     * 执行一个需要幂等的写操作。
     *
     * @param scope         幂等作用域，如 {@code "POST:/api/v1/projects/{projectId}/groups"}
     * @param key           客户端 {@code Idempotency-Key} 原值，为空返回 400
     * @param actor         当前认证用户
     * @param requestBody   用于计算请求指纹的请求体（DTO 对象）
     * @param successStatus 业务成功时的 HTTP 状态码
     * @param business      实际业务逻辑，返回已序列化为 ApiResponse 结构的 JSON
     * @return 本次或首次（回放）的响应 JSON
     */
    @Transactional
    public JsonNode run(String scope, String key, UUID actor, Object requestBody, int successStatus,
            Supplier<JsonNode> business) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "写操作必须携带 Idempotency-Key");
        }
        byte[] fingerprint = fingerprint(actor);
        byte[] requestHash = hash(requestBody);
        IdempotencyRecordEntity reservation = new IdempotencyRecordEntity();
        reservation.setId(UuidV7.next());
        reservation.setActorUserId(actor);
        reservation.setActorFingerprint(fingerprint);
        reservation.setScope(scope);
        reservation.setIdempotencyKey(key);
        reservation.setRequestHash(requestHash);
        reservation.setExpiresAt(utc(Instant.now().plus(TTL)));
        try {
            idempotencyMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            return replay(fingerprint, scope, key, requestHash);
        }
        JsonNode body = business.get();
        idempotencyMapper.update(null, Wrappers.<IdempotencyRecordEntity>lambdaUpdate()
                .set(IdempotencyRecordEntity::getResponseStatus, successStatus)
                .set(IdempotencyRecordEntity::getResponseBodyRedacted, body.toString())
                .eq(IdempotencyRecordEntity::getId, reservation.getId()));
        return body;
    }

    private JsonNode replay(byte[] fingerprint, String scope, String key, byte[] requestHash) {
        IdempotencyRecordEntity row = idempotencyMapper.selectOne(Wrappers.<IdempotencyRecordEntity>lambdaQuery()
                .eq(IdempotencyRecordEntity::getActorFingerprint, fingerprint)
                .eq(IdempotencyRecordEntity::getScope, scope)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, key)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "幂等请求冲突，请稍后重试");
        }
        if (!MessageDigest.isEqual(row.getRequestHash(), requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "幂等键已被不同请求使用");
        }
        if (row.getResponseBodyRedacted() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "请求正在处理中，请稍后重试");
        }
        try {
            return mapper.readTree(row.getResponseBodyRedacted());
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应记录损坏", e);
        }
    }

    private byte[] fingerprint(UUID actor) {
        return hash(actor.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hash(Object body) {
        return sha256(canonical(body));
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] canonical(Object body) {
        if (body instanceof byte[] b) {
            return b;
        }
        try {
            return mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化幂等请求体", e);
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
