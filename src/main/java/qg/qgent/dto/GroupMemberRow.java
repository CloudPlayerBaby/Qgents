package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.UUID;

/**
 * 群成员行：项目成员 join 用户基础信息（群成员即项目成员，无角色）。
 * <p>
 * 作为 {@code ProjectMemberMapper.selectMembers} 的映射结果，由 Service 转换为 {@link GroupMemberResponse}。
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
}
