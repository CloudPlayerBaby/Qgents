package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

/** GitHub OAuth 授权跳转信息；不包含任何访问凭证。 */
@Data
@AllArgsConstructor
public class GitHubOAuthStartResponse {
    @Schema(description = "GitHub OAuth 授权地址")
    private String authorizationUrl;
    @Schema(description = "state 过期时间")
    private OffsetDateTime expiresAt;
}
