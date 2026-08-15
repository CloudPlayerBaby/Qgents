package qg.qgent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 私钥解密器，用于解密客户端使用公钥加密的密码
 * RsaPasswordDecryptor
 */
@Component
public class RsaPasswordDecryptor {
    private static final Logger log = LoggerFactory.getLogger(RsaPasswordDecryptor.class);

    private final PrivateKey privateKey;
    private final String keyId;

    // 构造函数，加载RSA私钥和密钥ID
    public RsaPasswordDecryptor(@Value("${app.rsa-private-key}") Resource resource,
                                @Value("${app.rsa-key-id}") String keyId) {
        this.keyId = keyId;
        try {
            String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("无法加载RSA私钥", e);
        }
    }

    // 解密方法，使用RSA私钥解密客户端发送的加密密码
    public String decrypt(String requestedKeyId, String encrypted) {
        if (!keyId.equals(requestedKeyId)) {
            log.warn("Rejected encrypted password: requestedKeyId={}, expectedKeyId={}, passwordPresent={}, passwordLength={}",
                    requestedKeyId, keyId, encrypted != null, encrypted == null ? 0 : encrypted.length());
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_ENCRYPTED_PASSWORD", "密码密文或密钥版本不合法");
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decrypt encrypted password: keyId={}, passwordPresent={}, passwordLength={}, failure={}",
                    requestedKeyId, encrypted != null, encrypted == null ? 0 : encrypted.length(),
                    e.getClass().getSimpleName());
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_ENCRYPTED_PASSWORD", "密码密文或密钥版本不合法");
        }
    }
}
