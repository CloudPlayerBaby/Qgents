package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub API 的可选 HTTP 代理配置。
 *
 * <p>仅 GitHub 客户端使用该代理，Sandbox Worker 等内部服务仍按各自配置连接。
 * host 和 port 同时合法时才启用，未配置时保持直连，避免把本地代理带入部署环境。</p>
 */
@ConfigurationProperties("github.proxy")
public class GitHubProxyProperties {
    private String host = "";
    private int port = 0;

    public boolean configured() {
        return host != null && !host.isBlank() && port >= 1 && port <= 65535;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
