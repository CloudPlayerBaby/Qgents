package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** GitHub OAuth 用户授权配置；所有敏感值只能由环境变量或 Secret Manager 注入。 */
@ConfigurationProperties("github.oauth")
public class GitHubOAuthProperties {
    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String authorizeUrl = "https://github.com/login/oauth/authorize";
    private String tokenUrl = "https://github.com/login/oauth/access_token";
    private String userUrl = "https://api.github.com/user";
    private String callbackUrl = "";
    private String stateSecret = "";
    private String tokenEncryptionKey = "";
    /** 默认建仓为私有仓库，因此需要 GitHub 的 repo scope。 */
    private String scopes = "repo";
    private long stateTtlSeconds = 600;

    public boolean configured() {
        return enabled && hasText(clientId) && hasText(clientSecret) && hasText(callbackUrl)
                && hasText(stateSecret) && validEncryptionKey();
    }

    public List<String> scopeList() {
        String raw = scopes == null ? "" : scopes;
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private boolean validEncryptionKey() {
        if (!hasText(tokenEncryptionKey)) return false;
        try {
            return Base64.getDecoder().decode(tokenEncryptionKey).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getAuthorizeUrl() { return authorizeUrl; }
    public void setAuthorizeUrl(String authorizeUrl) { this.authorizeUrl = authorizeUrl; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getUserUrl() { return userUrl; }
    public void setUserUrl(String userUrl) { this.userUrl = userUrl; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public String getStateSecret() { return stateSecret; }
    public void setStateSecret(String stateSecret) { this.stateSecret = stateSecret; }
    public String getTokenEncryptionKey() { return tokenEncryptionKey; }
    public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public long getStateTtlSeconds() { return stateTtlSeconds; }
    public void setStateTtlSeconds(long stateTtlSeconds) { this.stateTtlSeconds = stateTtlSeconds; }
}
