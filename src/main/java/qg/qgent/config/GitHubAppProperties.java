package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("github.app")
public class GitHubAppProperties {
    private String appId = "";
    private String slug = "";
    private String privateKeyPath = "";
    private String callbackUrl = "";
    private String stateSecret = "";

    public boolean configured() {
        return !appId.isBlank() && !slug.isBlank() && !privateKeyPath.isBlank() && !callbackUrl.isBlank()
                && !stateSecret.isBlank();
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getStateSecret() {
        return stateSecret;
    }

    public void setStateSecret(String stateSecret) {
        this.stateSecret = stateSecret;
    }
}
