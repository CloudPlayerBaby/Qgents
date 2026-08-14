package qg.qgent.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端装配。
 * <p>
 * 仅当 aliyun.oss.enabled=true 时创建 OSSClient（避免开发环境未配置 OSS 时启动失败）；
 * 密钥来自环境变量（{@link AliyunOssProperties}），本类不接触任何密钥明文。
 */
@Configuration
@EnableConfigurationProperties(AliyunOssProperties.class)
public class AliyunOssConfiguration {

    /** 创建全局单例 OSSClient（线程安全、连接池内部复用）。 */
    @Bean
    @ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
    public OSS aliyunOssClient(AliyunOssProperties properties) {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "aliyun.oss 已启用但缺少 endpoint/bucket-name/access-key-id/access-key-secret 配置（请通过环境变量设置）");
        }
        return new OSSClientBuilder().build(properties.getEndpoint(), properties.getAccessKeyId(),
                properties.getAccessKeySecret());
    }
}
