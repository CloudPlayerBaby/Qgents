package qg.qgent.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.config.AliyunOssProperties;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 阿里云 OSS 版 Diff 快照存储（{@code @Primary}，OSS 启用时替代本地磁盘实现）。
 * <p>
 * Diff/Preview 的不可变 patch 快照改为存 OSS，避免本地磁盘在多实例部署或容器重启时
 * 丢失（快照写入实例与读取实例不一致会直接导致 /files、/file 读不到文件而报错）。
 * 对象键保持与 {@link LocalDiffSnapshotStorage} 一致（{@code diff-snapshots/<uuid>.patch}），
 * 便于迁移与排查。仅在 aliyun.oss.enabled=true 时生效；未启用时回退本地实现。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
public class OssDiffSnapshotStorage implements DiffSnapshotStorage {

    private static final String PREFIX = "diff-snapshots/";

    private final OSS oss;
    private final AliyunOssProperties properties;

    public OssDiffSnapshotStorage(OSS oss, AliyunOssProperties properties) {
        this.oss = oss;
        this.properties = properties;
    }

    @Override
    public String store(UUID diffId, String patch) {
        String key = PREFIX + diffId + ".patch";
        byte[] bytes = (patch == null ? "" : patch).getBytes(StandardCharsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("text/plain; charset=utf-8");
        metadata.setContentLength(bytes.length);
        try {
            oss.putObject(properties.getBucketName(), key, new ByteArrayInputStream(bytes), metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to persist Diff snapshot to OSS", e);
        }
        return key;
    }

    @Override
    public String load(String snapshotKey) {
        String key = validateKey(snapshotKey);
        try {
            byte[] bytes = oss.getObject(properties.getBucketName(), key).getObjectContent().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read Diff snapshot from OSS", e);
        }
    }

    private String validateKey(String key) {
        if (key == null || !key.matches(PREFIX + "[0-9a-fA-F-]{36}\\.patch")) {
            throw new IllegalArgumentException("Invalid Diff snapshot key");
        }
        return key;
    }
}
