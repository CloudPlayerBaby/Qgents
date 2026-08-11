package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.IdempotencyRecordEntity;
import qg.qgent.mapper.IdempotencyRecordMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 写接口幂等记录访问服务。
 * 同一调用者、作用域与 Idempotency-Key 在 24 小时内重复提交时回放首次结果；
 * 相同键但请求体不同时返回 409 IDEMPOTENCY_KEY_REUSED。
 */
@Service
public class IdempotencyService {
    private static final int EXPIRY_HOURS = 24;

    private final IdempotencyRecordMapper mapper;

    public IdempotencyService(IdempotencyRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按调用者指纹、作用域与键查询未过期的幂等记录。
     *
     * @return 已存在记录，未找到时返回 null
     */
    public IdempotencyRecordEntity find(byte[] fingerprint, String scope, String key) {
        return mapper.selectOne(Wrappers.<IdempotencyRecordEntity>lambdaQuery()
                .eq(IdempotencyRecordEntity::getActorFingerprint, fingerprint)
                .eq(IdempotencyRecordEntity::getScope, scope)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, key)
                .gt(IdempotencyRecordEntity::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 保存首次成功的幂等记录（默认有效期 24 小时）。
     *
     * @param userId        已认证调用用户ID，可为 null
     * @param fingerprint   调用者稳定指纹
     * @param scope         幂等业务作用域，如 HTTP 方法与路由模板
     * @param key           客户端 Idempotency-Key 原值
     * @param requestHash   规范化请求体 SHA-256
     * @param status        首次请求 HTTP 响应状态码
     * @param responseBody  脱敏响应 JSON（需确保不含 Token 或 Secret）
     * @param resourceId    首次请求创建或变更的资源UUID，可为 null
     */
    public void save(UUID userId, byte[] fingerprint, String scope, String key, byte[] requestHash, int status,
            Map<String, Object> responseBody, UUID resourceId) {
        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setId(UuidV7.next());
        record.setActorUserId(userId);
        record.setActorFingerprint(fingerprint);
        record.setScope(scope);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setResponseStatus(status);
        record.setResponseBodyRedacted(responseBody);
        record.setResourceId(resourceId);
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        record.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(EXPIRY_HOURS));
        mapper.insert(record);
    }
}
