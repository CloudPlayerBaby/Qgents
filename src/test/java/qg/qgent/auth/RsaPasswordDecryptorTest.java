package qg.qgent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class RsaPasswordDecryptorTest {
    @Test void decryptsFrontendPkcs1Payload() throws Exception {
        var pairGenerator=KeyPairGenerator.getInstance("RSA"); pairGenerator.initialize(2048);
        var pair=pairGenerator.generateKeyPair();
        Path privatePem=Files.createTempFile("qgents-test-private", ".pem");
        Files.writeString(privatePem, "-----BEGIN PRIVATE KEY-----\n"+
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded())+
                "\n-----END PRIVATE KEY-----\n");
        var decryptor=new RsaPasswordDecryptor(new FileSystemResource(privatePem), "rsa-test");
        Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,pair.getPublic());
        String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal("Password123".getBytes(StandardCharsets.UTF_8)));
        assertThat(decryptor.decrypt("rsa-test", encrypted)).isEqualTo("Password123");
        Files.deleteIfExists(privatePem);
    }

    @Test void rejectsMalformedCiphertext() throws Exception {
        var pairGenerator=KeyPairGenerator.getInstance("RSA"); pairGenerator.initialize(2048);
        Path privatePem=Files.createTempFile("qgents-test-private", ".pem");
        Files.writeString(privatePem, "-----BEGIN PRIVATE KEY-----\n"+
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pairGenerator.generateKeyPair().getPrivate().getEncoded())+
                "\n-----END PRIVATE KEY-----\n");
        var decryptor=new RsaPasswordDecryptor(new FileSystemResource(privatePem), "rsa-test");
        assertThatThrownBy(()->decryptor.decrypt("rsa-test", "not-base64")).isInstanceOf(qg.qgent.api.ApiException.class);
        assertThatThrownBy(()->decryptor.decrypt("wrong-key", "not-base64")).isInstanceOf(qg.qgent.api.ApiException.class);
        Files.deleteIfExists(privatePem);
    }
}
