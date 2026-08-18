package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.api.ApiException;
import qg.qgent.config.PushProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 对移动端设备 Token 做摘要和 AES-GCM 加密，防止明文进入数据库。 */
@Component
public class PushTokenCipher {
    private static final int IV_BYTES = 12;
    private final PushProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PushTokenCipher(PushProperties properties) {
        this.properties = properties;
    }

    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String encrypt(String token) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH_TOKEN_ENCRYPT_FAILED",
                    "设备推送凭证加密失败");
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException("invalid ciphertext");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH_TOKEN_DECRYPT_FAILED",
                    "设备推送凭证解密失败");
        }
    }

    private SecretKeySpec key() {
        try {
            String configured = properties.getTokenEncryptionKey();
            if (configured == null || configured.isBlank()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_NOT_CONFIGURED",
                        "离线推送服务尚未配置");
            }
            byte[] key = Base64.getDecoder().decode(configured);
            if (key.length != 32) throw new IllegalArgumentException("key must be 32 bytes");
            return new SecretKeySpec(key, "AES");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_NOT_CONFIGURED",
                    "离线推送加密密钥配置无效");
        }
    }
}
