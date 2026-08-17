package qg.qgent.service;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.config.AliyunOssProperties;

import java.net.URL;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AvatarStorageService 头像存储测试：OSS 未启用 501、媒体类型/大小校验、对象键命名空间、确认与公共读 URL。
 */
class AvatarStorageServiceTest {

    private final AliyunOssProperties properties = new AliyunOssProperties();
    private final ObjectProvider<OSS> provider = mock(ObjectProvider.class);
    private final OSS oss = mock(OSS.class);

    private AvatarStorageService disabled() {
        return new AvatarStorageService(provider, properties);
    }

    private AvatarStorageService enabled() {
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-guangzhou.aliyuncs.com");
        properties.setBucketName("my-bucket");
        properties.setAccessKeyId("a");
        properties.setAccessKeySecret("b");
        properties.setPresignExpirySeconds(900);
        when(provider.getObject()).thenReturn(oss);
        return new AvatarStorageService(provider, properties);
    }

    @Test
    void credentialThrows501WhenOssDisabled() {
        assertThatThrownBy(() -> disabled().createCredential(UUID.randomUUID(), "image/png", 1000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(ex.code()).isEqualTo("AVATAR_STORAGE_NOT_CONFIGURED");
                });
    }

    @Test
    void credentialRejectsNonImageMediaType() {
        assertThatThrownBy(() -> enabled().createCredential(UUID.randomUUID(), "text/plain", 1000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("AVATAR_MEDIA_TYPE_REJECTED"));
    }

    @Test
    void credentialRejectsOversized() {
        assertThatThrownBy(() -> enabled().createCredential(UUID.randomUUID(), "image/png",
                AvatarStorageService.MAX_SIZE_BYTES + 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void credentialGeneratesServerSideOwnPrefixObjectKey() throws Exception {
        UUID userId = UUID.randomUUID();
        when(oss.generatePresignedUrl(eq("my-bucket"), any(String.class), any(java.util.Date.class), any()))
                .thenReturn(new URL("https://put-url"));
        AvatarStorageService.AvatarCredential credential =
                enabled().createCredential(userId, "image/jpeg", 1000L);

        assertThat(credential.objectKey()).startsWith("avatars/" + userId + "/");
        assertThat(credential.objectKey()).endsWith(".jpg");
        assertThat(credential.credential().getUploadUrl()).isEqualTo("https://put-url");
    }

    @Test
    void confirmRejectsNotUploadedObject() {
        when(oss.doesObjectExist("my-bucket", "avatars/u/x.png")).thenReturn(false);
        assertThatThrownBy(() -> enabled().confirmAvatar("avatars/u/x.png"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("AVATAR_NOT_UPLOADED");
                });
    }

    @Test
    void confirmThrows501WhenPublicBaseUrlMissing() {
        when(oss.doesObjectExist("my-bucket", "avatars/u/x.png")).thenReturn(true);
        assertThatThrownBy(() -> enabled().confirmAvatar("avatars/u/x.png"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("AVATAR_PUBLIC_URL_NOT_CONFIGURED"));
    }

    @Test
    void confirmReturnsPublicReadUrl() {
        properties.setPublicBaseUrl("https://cdn.example.com/");
        when(oss.doesObjectExist(eq("my-bucket"), any(String.class))).thenReturn(true);
        assertThat(enabled().confirmAvatar("avatars/u/x.png"))
                .isEqualTo("https://cdn.example.com/avatars/u/x.png");
    }
}
