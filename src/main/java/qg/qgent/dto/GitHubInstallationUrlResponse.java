package qg.qgent.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 安装跳转地址。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationUrlResponse {
    /** GitHub App 安装页面地址，含短时有效的签名 state。 */
    private String installationUrl;

    /** 安装跳转地址失效时间，UTC。 */
    private Instant expiresAt;
}
