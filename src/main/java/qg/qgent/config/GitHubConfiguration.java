package qg.qgent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubOAuthClient;
import qg.qgent.github.RestGitHubAppClient;
import qg.qgent.github.RestGitHubOAuthClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({GitHubAppProperties.class, GitHubOAuthProperties.class,
        GitHubWebhookProperties.class, GitHubProxyProperties.class})
public class GitHubConfiguration {

    /**
     * GitHub API 连接超时。必须显著小于 Webhook RECEIVED 的失效阈值（5 分钟），
     * 避免原请求在 GitHub 调用上卡住超过阈值时，重投与原请求并发写业务。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * GitHub API 读取超时。同上，防止请求长时间挂起导致 Webhook 领取租约并发失效。
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GitHubAppClient gitHubAppClient(GitHubAppProperties properties, GitHubProxyProperties proxyProperties, Clock clock) {
        HttpClient httpClient = createHttpClient(proxyProperties);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        RestClient client = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(factory)
                .build();
        return new RestGitHubAppClient(client, properties, clock);
    }

    @Bean
    GitHubOAuthClient gitHubOAuthClient(GitHubOAuthProperties properties, GitHubProxyProperties proxyProperties) {
        HttpClient httpClient = createHttpClient(proxyProperties);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        RestClient oauthClient = RestClient.builder().requestFactory(factory).build();
        RestClient apiClient = RestClient.builder().baseUrl("https://api.github.com")
                .requestFactory(factory).build();
        return new RestGitHubOAuthClient(oauthClient, apiClient, properties);
    }

    static HttpClient createHttpClient(GitHubProxyProperties proxyProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (proxyProperties.configured()) {
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(
                    proxyProperties.getHost(), proxyProperties.getPort())));
        }
        return builder.build();
    }
}
