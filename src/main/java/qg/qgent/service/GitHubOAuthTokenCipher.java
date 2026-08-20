package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubOAuthProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** GitHub OAuth Token 的 AES-GCM 密文组件；密钥缺失或密文损坏时 fail-closed。 */
@Component
public class GitHubOAuthTokenCipher {
    private static final int IV_BYTES = 12;
    private final GitHubOAuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public GitHubOAuthTokenCipher(GitHubOAuthProperties properties) { this.properties = properties; }

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
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_OAUTH_NOT_CONFIGURED",
                    "GitHub OAuth 加密配置无效");
        }
    }

    public String decrypt(String ciphertext) {
        try {
            if (ciphertext == null || !ciphertext.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(ciphertext.substring(3));
            if (packed.length <= IV_BYTES) throw new IllegalArgumentException();
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GITHUB_OAUTH_TOKEN_CORRUPTED",
                    "GitHub OAuth 授权密文不可用");
        }
    }

    private SecretKeySpec key() {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getTokenEncryptionKey());
            if (key.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_OAUTH_NOT_CONFIGURED",
                    "GitHub OAuth 加密密钥配置无效");
        }
    }
}
