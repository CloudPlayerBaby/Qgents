package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 需求群显式成员关系（用户），对应表 group_members（契约 §7 群成员选择与管理补充）。
 * <p>
 * 群成员从「全部项目成员」解耦为可管理的显式关系：REQUIREMENT 需求群成员 =
 * 本表用户 + group_agents 的 Agent；PROJECT_MAIN 主群不写入本表，成员恒为全部项目成员
 * （系统管理，不提供成员管理接口）。复合主键（requirement_group_id, user_id）。
 */
@Data
@TableName("group_members")
public class GroupMemberEntity {

    /**
     * 需求群 ID（复合主键之一）。
     */
    @TableId
    private UUID requirementGroupId;

    /**
     * 项目成员用户 ID（复合主键之一）。
     */
    @TableId
    private UUID userId;

    /**
     * 加入时间（UTC）。
     */
    private LocalDateTime joinedAt;
}
