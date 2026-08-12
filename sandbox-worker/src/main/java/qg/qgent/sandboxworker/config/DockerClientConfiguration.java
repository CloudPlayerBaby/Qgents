package qg.qgent.sandboxworker.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Docker Engine 客户端配置，仅在显式启用 Docker 运行时时加载。
 */
@Configuration
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "docker")
public class DockerClientConfiguration {

    /**
     * 创建供容器生命周期和命令执行共同使用的 Docker 客户端。
     */
    @Bean(destroyMethod = "close")
    DockerClient dockerClient(SandboxWorkerProperties properties) {
        // 根据 Docker Host 配置创建 Docker 客户端配置
        var config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(properties.getDockerHost())
                .build();
        // 创建 Docker 客户端传输层
        var transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(URI.create(properties.getDockerHost()))
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .maxConnections(32)
                .build();
        // 形成最终的 DockerClient 实例，包含配置和传输层
        return DockerClientImpl.getInstance(config, transport);
    }
}
