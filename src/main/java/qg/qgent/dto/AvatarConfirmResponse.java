package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认头像上传后的响应：返回长期稳定、公共可读的头像 URL（前端可直接用于展示头像）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvatarConfirmResponse {

    /**
     * 头像公共读长期 URL。
     */
    @Schema(description = "头像公共读长期 URL")
    private String avatarUrl;
}
