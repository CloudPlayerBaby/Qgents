package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群成员视图（契约 §7 群成员列表）。
 * <p>
 * 群成员取自项目成员，群内成员平等、无角色区分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberResponse {

    /** 成员用户 ID。 */
    private String id;

    /** 成员昵称。 */
    private String displayName;

    /** 成员头像 URL，可为空。 */
    private String avatarUrl;
}
