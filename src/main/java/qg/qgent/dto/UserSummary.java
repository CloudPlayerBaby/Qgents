package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    /**
     * 用户 ID。
     */
    @Schema(description = "用户 ID")
    private String id;

    /**
     * 昵称。
     */
    @Schema(description = "昵称")
    private String displayName;

    /**
     * 头像 URL，可为空。
     */
    @Schema(description = "头像 URL")
    private String avatarUrl;
}
