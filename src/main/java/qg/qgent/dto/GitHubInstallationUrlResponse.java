package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * GitHub App 安装跳转地址及其短期有效期。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationUrlResponse {
    /**
     * GitHub App 安装页面地址，包含短期有效的签名 state。
     */
    private String installationUrl;

    /**
     * 安装跳转地址的失效时间（UTC）。
     */
    private OffsetDateTime expiresAt;
}
