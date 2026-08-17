package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.UUID;

/**
 * 群成员行：项目/群成员 join 用户基础信息（群成员无角色）。
 * <p>
 * 作为 {@code ProjectMemberMapper.selectMembers} / {@code GroupMemberMapper.selectMembersWithUsers}
 * 的映射结果，由 Service 转换为 {@link GroupMemberResponse}。email 仅在群成员管理场景
 * （需求群显式成员）填充；项目成员列表查询不查 email 时为 null。
 */
@Data
public class GroupMemberRow {

    /**
     * 成员用户 ID。
     */
    @Schema(description = "成员用户 ID")
    private UUID userId;

    /**
     * 成员昵称。
     */
    @Schema(description = "成员昵称")
    private String displayName;

    /**
     * 成员头像 URL，可为空。
     */
    @Schema(description = "成员头像 URL")
    private String avatarUrl;

    /**
     * 成员登录邮箱（仅群成员管理场景填充；AGENT 成员为 null）。
     */
    @Schema(description = "成员登录邮箱（仅 USER 群成员；AGENT 为 null）")
    private String email;
}
