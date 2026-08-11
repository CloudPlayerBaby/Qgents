package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("idempotency_records")
public class IdempotencyRecordEntity {
    @TableId(type = IdType.INPUT) private UUID id;
    private UUID actorUserId;
    private byte[] actorFingerprint;
    private String scope;
    private String idempotencyKey;
    private byte[] requestHash;
    private Integer responseStatus;
    private String responseBodyRedacted;
    private UUID resourceId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
