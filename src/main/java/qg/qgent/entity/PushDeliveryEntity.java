package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 一条通知到一个设备的可恢复推送投递事实。 */
@Data
@TableName("push_deliveries")
public class PushDeliveryEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID notificationId;
    private UUID deviceId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String providerMessageId;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime sentAt;
}
