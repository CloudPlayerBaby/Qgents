package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Cookie 认证模式下返回给浏览器的会话摘要，不包含任何认证凭证。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthSessionResponse {
    @Schema(description = "当前登录用户")
    private UserResponse user;
}
