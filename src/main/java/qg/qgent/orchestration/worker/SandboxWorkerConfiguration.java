package qg.qgent.orchestration.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import qg.qgent.config.PerformanceMetrics;

import java.time.Duration;

/**
 * Worker 客户端装配：启用 {@link SandboxWorkerProperties} 并基于 {@link RestClient#builder()}
 * 静态工厂构建 {@link SandboxWorkerClient}（与 {@code GitHubConfiguration} 的既有约定一致，
 * 不依赖额外的 RestClient.Builder Bean）。调用鉴权只取决于是否配置服务间令牌，不能因开关遗漏
 * Bearer Token 而产生难以定位的 401。
 * <p>
 * 为 RestClient 配置连接/响应超时（来自 {@code app.worker.connect-timeout/response-timeout}）：
 * Worker 请求挂起时会在超时内转为 {@code SANDBOX_WORKER_UNAVAILABLE}，避免长期占住编排线程，
 * 而不是无限等待。
 */
@Configuration
@EnableConfigurationProperties(SandboxWorkerProperties.class)
public class SandboxWorkerConfiguration {

    @Bean
    SandboxWorkerClient sandboxWorkerClient(SandboxWorkerProperties properties, ObjectMapper objectMapper,
                                            ObjectProvider<PerformanceMetrics> metricsProvider) {
        RestClient client = workerClient(properties, properties.getResponseTimeout());
        return new SandboxWorkerClient(client, objectMapper, metricsProvider.getIfAvailable(),
                timeout -> workerClient(properties, timeout));
    }

    /**
     * 普通 Worker API 使用短读取超时；同步 Testset API 由调用方传入本次运行的总预算。
     */
    private RestClient workerClient(SandboxWorkerProperties properties, Duration responseTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout(Math.toIntExact(responseTimeout.toMillis()));
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
        if (properties.getBackendServiceToken() != null
                && !properties.getBackendServiceToken().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(properties.getBackendServiceToken()));
        }
        return builder.build();
    }
}
