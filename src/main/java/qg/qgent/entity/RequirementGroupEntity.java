package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 需求群/项目主群实体，对应表 requirement_groups（契约 §7 统一 Group 模型）。
 * <p>
 * PROJECT_MAIN 与 REQUIREMENT 统一建模：项目创建时服务端自动创建唯一 PROJECT_MAIN 群；
 * 仅 REQUIREMENT 可归档，PROJECT_MAIN 恒为 ACTIVE（由表级 CHECK 与唯一生成列保障）。
 */
@Data
@TableName("requirement_groups")
public class RequirementGroupEntity {

    /** 群 ID（UUIDv7，BINARY(16)）。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属项目 ID（UUIDv7，BINARY(16)）。 */
    private UUID projectId;

    /** 创建用户 ID（UUIDv7，BINARY(16)）；PROJECT_MAIN 由服务端创建时使用项目创建者。 */
    private UUID createdBy;

    /** 群标题（契约字段 title，落库列 name），PROJECT_MAIN 默认取项目名。 */
    private String name;

    /** 群目标和需求背景说明，可为空。 */
    private String description;

    /** 群类型枚举：PROJECT_MAIN / REQUIREMENT。 */
    private String groupType;

    /** 群状态枚举：ACTIVE / ARCHIVED；PROJECT_MAIN 不允许 ARCHIVED。 */
    private String status;

    /** 最近消息时间（UTC），用于列表按最近活跃排序；从未发言的群为空。 */
    private LocalDateTime lastMessageAt;

    /** 创建时间（UTC，数据库默认值）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，数据库 ON UPDATE 维护）。 */
    private LocalDateTime updatedAt;
}
