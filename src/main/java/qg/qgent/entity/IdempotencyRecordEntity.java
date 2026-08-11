package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 写接口幂等记录实体，对应表 idempotency_records（契约 §2 写操作幂等）。
 * <p>
 * 同一 (actor_fingerprint, scope, idempotency_key) 24 小时内唯一；相同键不同请求体返回 409。
 * 响应体仅保存脱敏 JSON，不含 Token、密码等 Secret。
 */
@Data
@TableName("idempotency_records")
public class IdempotencyRecordEntity {

    /** 幂等记录 ID（UUIDv7，BINARY(16)）。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 已认证调用用户 ID（UUIDv7，BINARY(16)）；匿名请求为空。 */
    private UUID actorUserId;

    /** 调用者稳定指纹 SHA-256（BINARY(32)），参与幂等唯一键。 */
    private byte[] actorFingerprint;

    /** 幂等业务作用域，如 HTTP 方法与路由模板。 */
    private String scope;

    /** 客户端 Idempotency-Key 原值。 */
    private String idempotencyKey;

    /** 规范化请求体 SHA-256（BINARY(32)），用于检测同键不同请求。 */
    private byte[] requestHash;

    /** 首次请求 HTTP 响应状态码，业务成功后才写入。 */
    private Integer responseStatus;

    /** 首次响应脱敏 JSON 文本（JSON 列），业务成功后才写入。 */
    private String responseBodyRedacted;

    /** 首次请求创建或变更的资源 ID（UUIDv7，BINARY(16)）；可为空。 */
    private UUID resourceId;

    /** 幂等记录失效时间（UTC），24 小时后可重新使用同键。 */
    private LocalDateTime expiresAt;
}
