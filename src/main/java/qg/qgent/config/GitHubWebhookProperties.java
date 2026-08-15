package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub Webhook 接收配置。
 * Secret 只从环境变量或受控 Secret 注入，禁止写入仓库；缺失时 Webhook 接口必须 fail-closed（503）。
 */
@ConfigurationProperties("github.webhook")
public class GitHubWebhookProperties {

    /**
     * GitHub App Webhook Secret（HMAC-SHA256 验签密钥），值来自 GITHUB_WEBHOOK_SECRET。
     */
    private String secret = "";

    /**
     * 单次 Webhook 请求体允许的最大字节数，超过返回 413 且不落投递业务数据。
     */
    private int maxBodyBytes = 1024 * 1024;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * Secret 是否已配置；未配置时 Webhook 接口不可用。
     */
    public boolean secretConfigured() {
        return secret != null && !secret.isBlank();
    }
}
