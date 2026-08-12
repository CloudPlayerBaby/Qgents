package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户摘要，用于 Skill/Memory 响应中的创建者、审核者展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSummary {

    /** 用户 ID。 */
    private String id;

    /** 昵称。 */
    private String displayName;

    /** 头像 URL，可为空。 */
    private String avatarUrl;
}
