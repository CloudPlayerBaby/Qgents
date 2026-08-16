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
 * 通知级 SSE 事件实体（前端 SSE 需求清单 ③）。
 * <p>
 * 按用户维度持久化通知事件；sequence_no 为该用户内单调递增游标，SSE 用 Last-Event-ID 续传。
 */
@Data
@TableName(value = "notification_events", autoResultMap = true)
public class NotificationEventEntity {

    /**
     * 事件 ID（UUIDv7）。
     */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /**
     * 接收通知的用户 ID。
     */
    private UUID recipientUserId;

    /**
     * 用户内单调递增事件序号（SSE 游标）。
     */
    private Long sequenceNo;

    /**
     * 关联通知 ID；可为空。
     */
    private UUID notificationId;

    /**
     * 通知类型（TASK_COMPLETED/INVITED 等）。
     */
    private String kind;

    /**
     * 脱敏事件载荷。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    /**
     * 产生时间（UTC）。
     */
    private LocalDateTime createdAt;
}
