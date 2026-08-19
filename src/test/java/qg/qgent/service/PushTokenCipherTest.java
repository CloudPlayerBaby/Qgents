package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.config.PushProperties;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 推送 Token 必须以不可逆摘要和随机 AES-GCM 密文持久化。 */
class PushTokenCipherTest {

    @Test
    void encryptsWithoutPersistingPlaintextAndCanDecrypt() {
        PushProperties properties = new PushProperties();
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        PushTokenCipher cipher = new PushTokenCipher(properties);
        String token = "test-device-token";

        String first = cipher.encrypt(token);
        String second = cipher.encrypt(token);

        assertThat(first).doesNotContain(token).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(token);
        assertThat(cipher.hash(token)).hasSize(64).doesNotContain(token);
    }
}
