package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 写接口幂等请求与脱敏响应缓存。
 * 同一调用者（actorFingerprint）、作用域与 Idempotency-Key 在 24 小时内重复提交时
 * 回放首次响应；相同键但请求体不同时返回 409 IDEMPOTENCY_KEY_REUSED。
 */
@Data
@TableName(value = "idempotency_records", autoResultMap = true)
public class IdempotencyRecordEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 已认证调用用户ID，匿名请求为空。 */
    private UUID actorUserId;
    /** 调用者稳定指纹 SHA-256。 */
    private byte[] actorFingerprint;
    /** 幂等业务作用域，如 HTTP 方法与路由模板。 */
    private String scope;
    /** 客户端 Idempotency-Key 原值。 */
    private String idempotencyKey;
    /** 规范化请求体 SHA-256，用于检测同键不同请求。 */
    private byte[] requestHash;
    /** 首次请求 HTTP 响应状态码。 */
    private Integer responseStatus;
    /** 确认不含 Token 或 Secret 的脱敏响应 JSON。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responseBodyRedacted;
    /** 首次请求创建或变更的资源 UUID。 */
    private UUID resourceId;
    private LocalDateTime createdAt;
    /** 幂等记录失效时间（UTC），默认 24 小时。 */
    private LocalDateTime expiresAt;
}
