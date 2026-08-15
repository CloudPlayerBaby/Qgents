package qg.qgent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 团队成员视图。补充用户展示所需字段：
 * displayName（显示名，前端通讯录依赖）、email（邮箱）。
 * 保留 (userId, role) 两参构造函数兼容既有调用，其余字段通过 setter 填充。
 */
@Data
@NoArgsConstructor
public class TeamMemberResponse {
    private String userId;
    private String role;
    /**
     * 成员用户显示名（users.display_name）。
     */
    private String displayName;
    /**
     * 成员用户邮箱（users.email）。
     */
    private String email;

    public TeamMemberResponse(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }
}
