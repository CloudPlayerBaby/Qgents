package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 项目讨论与协作上下文群（统一群模型）。
 * groupType 枚举：PROJECT_MAIN/REQUIREMENT；status 枚举：ACTIVE/ARCHIVED。
 * 仅 REQUIREMENT 可归档，PROJECT_MAIN 永远保持 ACTIVE。
 */
@Data
@TableName("requirement_groups")
public class RequirementGroupEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属项目ID。
     */
    private UUID projectId;
    /**
     * 创建用户ID。
     */
    private UUID createdBy;
    /**
     * 群聊名称。
     */
    private String name;
    /**
     * 群聊目标和需求背景说明。
     */
    private String description;
    /**
     * 群类型：PROJECT_MAIN/REQUIREMENT。
     */
    private String groupType;
    /**
     * 群状态：ACTIVE/ARCHIVED。
     */
    private String status;
    /**
     * 最近消息时间（UTC），无消息时为空。
     */
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
