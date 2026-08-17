package qg.qgent.orchestration.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Worker 客户端装配：启用 {@link SandboxWorkerProperties} 并基于 {@link RestClient#builder()}
 * 静态工厂构建 {@link SandboxWorkerClient}（与 {@code GitHubConfiguration} 的既有约定一致，
 * 不依赖额外的 RestClient.Builder Bean）。客户端为惰性 Bean，未启用 Worker 端口时不产生外部调用。
 * <p>
 * 为 RestClient 配置连接/响应超时（来自 {@code app.worker.connect-timeout/response-timeout}）：
 * Worker 请求挂起时会在超时内转为 {@code SANDBOX_WORKER_UNAVAILABLE}，避免长期占住编排线程，
 * 而不是无限等待。
 */
@Configuration
@EnableConfigurationProperties(SandboxWorkerProperties.class)
public class SandboxWorkerConfiguration {

    @Bean
    SandboxWorkerClient sandboxWorkerClient(SandboxWorkerProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getResponseTimeout().toMillis());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
        if (properties.isEnabled() && properties.getBackendServiceToken() != null
                && !properties.getBackendServiceToken().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(properties.getBackendServiceToken()));
        }
        RestClient client = builder.build();
        return new SandboxWorkerClient(client, objectMapper);
    }
}
