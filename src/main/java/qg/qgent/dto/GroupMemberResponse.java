package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群成员视图（契约 §7 群成员列表）。
 * <p>
 * 群成员 = 项目成员（真实用户）+ 参与群聊的 Agent，群内成员平等、无角色区分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberResponse {

    /**
     * 成员 ID（用户 ID 或 Agent ID）。
     */
    @Schema(description = "成员 ID（用户 ID 或 Agent ID）")
    private String id;

    /**
     * 成员昵称（用户 displayName 或 Agent name）。
     */
    @Schema(description = "成员昵称")
    private String displayName;

    /**
     * 成员头像 URL，可为空（Agent 当前无头像字段）。
     */
    @Schema(description = "成员头像 URL")
    private String avatarUrl;

    /**
     * 成员登录邮箱；仅 USER 成员返回，AGENT 成员为 null（前端按成员类型隐藏邮箱行）。
     */
    @Schema(description = "成员登录邮箱（仅 USER；AGENT 为 null）")
    private String email;

    /**
     * 成员类型：USER / AGENT。
     */
    @Schema(description = "成员类型：USER / AGENT")
    private String memberType;
}
