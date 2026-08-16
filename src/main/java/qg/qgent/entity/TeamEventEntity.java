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
 * 团队级 SSE 事件实体（前端 SSE 需求清单 ②）。
 * <p>
 * 按团队维度持久化事件；sequence_no 为该团队内单调递增游标，SSE 用 Last-Event-ID 续传。
 */
@Data
@TableName(value = "team_events", autoResultMap = true)
public class TeamEventEntity {

    /**
     * 事件 ID（UUIDv7）。
     */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /**
     * 所属团队 ID。
     */
    private UUID teamId;

    /**
     * 团队内单调递增事件序号（SSE 游标）。
     */
    private Long sequenceNo;

    /**
     * 事件类型（project.member.added / team.member.updated / activity.created）。
     */
    private String eventType;

    /**
     * 关联资源 ID（projectId 等）；可为空。
     */
    private String resourceId;

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
