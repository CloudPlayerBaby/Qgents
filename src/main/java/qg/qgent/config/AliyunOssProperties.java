package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 对象存储配置（附件真实存储，prefix=aliyun.oss）。
 * <p>
 * AccessKeyId/AccessKeySecret 等敏感项一律通过环境变量注入（application.yaml 只保留 ${ALIYUN_OSS_*} 占位），
 * 严禁写死密钥或提交到版本库。enabled=false 时回退到本地签名开发策略，不创建 OSS 客户端。
 */
@ConfigurationProperties("aliyun.oss")
public class AliyunOssProperties {

    /**
     * 是否启用阿里云 OSS 存储；false 时使用本地签名开发策略。
     */
    private boolean enabled = false;

    /**
     * OSS Endpoint，如 https://oss-cn-guangzhou.aliyuncs.com。
     */
    private String endpoint = "";

    /**
     * 存储桶名称。
     */
    private String bucketName = "";

    /**
     * AccessKey ID（环境变量注入）。
     */
    private String accessKeyId = "";

    /**
     * AccessKey Secret（环境变量注入）。
     */
    private String accessKeySecret = "";

    /**
     * 预签名 URL 有效期（秒），默认 900（15 分钟）。
     */
    private long presignExpirySeconds = 900;

    /**
     * 桶内公共读对象的访问基础 URL（如 https://my-bucket.oss-cn-guangzhou.aliyuncs.com 或自定义域名）。
     * 头像等长生命周期公共读对象用该基础 URL + objectKey 拼出长期稳定地址；不从 endpoint 运行时推导
     * （虚拟主机式/路径式/自定义域名无法仅凭 endpoint 判定）。启用 OSS 且需要公共读对象时必须配置。
     */
    private String publicBaseUrl = "";

    public boolean configured() {
        return !endpoint.isBlank() && !bucketName.isBlank() && !accessKeyId.isBlank() && !accessKeySecret.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public long getPresignExpirySeconds() {
        return presignExpirySeconds;
    }

    public void setPresignExpirySeconds(long presignExpirySeconds) {
        this.presignExpirySeconds = presignExpirySeconds;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
