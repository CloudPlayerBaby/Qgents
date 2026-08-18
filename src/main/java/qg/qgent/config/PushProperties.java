package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 移动端离线推送配置。所有凭证仅允许通过受控环境变量注入。
 */
@Component
@ConfigurationProperties(prefix = "qgents.push")
public class PushProperties {
    private boolean enabled;
    private String fcmEndpoint = "https://fcm.googleapis.com/v1/projects";
    private String fcmProjectId;
    private String fcmServiceAccountJsonBase64;
    private String tokenEncryptionKey;
    private int maxAttempts = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFcmEndpoint() { return fcmEndpoint; }
    public void setFcmEndpoint(String fcmEndpoint) { this.fcmEndpoint = fcmEndpoint; }
    public String getFcmProjectId() { return fcmProjectId; }
    public void setFcmProjectId(String fcmProjectId) { this.fcmProjectId = fcmProjectId; }
    public String getFcmServiceAccountJsonBase64() { return fcmServiceAccountJsonBase64; }
    public void setFcmServiceAccountJsonBase64(String fcmServiceAccountJsonBase64) {
        this.fcmServiceAccountJsonBase64 = fcmServiceAccountJsonBase64;
    }
    public String getTokenEncryptionKey() { return tokenEncryptionKey; }
    public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    /** 是否具备真实投递所需配置。 */
    public boolean deliveryConfigured() {
        return enabled && hasText(fcmEndpoint) && hasText(fcmProjectId)
                && hasText(fcmServiceAccountJsonBase64) && hasText(tokenEncryptionKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
