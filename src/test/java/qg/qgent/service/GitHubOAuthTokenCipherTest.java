package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubOAuthProperties;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubOAuthTokenCipherTest {
    @Test
    void encryptsAndDetectsTampering() {
        GitHubOAuthProperties properties = new GitHubOAuthProperties();
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        GitHubOAuthTokenCipher cipher = new GitHubOAuthTokenCipher(properties);

        String encrypted = cipher.encrypt("github-user-token");
        assertEquals("github-user-token", cipher.decrypt(encrypted));

        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");
        ApiException exception = assertThrows(ApiException.class, () -> cipher.decrypt(tampered));
        assertEquals("GITHUB_OAUTH_TOKEN_CORRUPTED", exception.code());
    }

    @Test
    void failsClosedWhenEncryptionKeyIsMissing() {
        GitHubOAuthTokenCipher cipher = new GitHubOAuthTokenCipher(new GitHubOAuthProperties());

        ApiException exception = assertThrows(ApiException.class, () -> cipher.encrypt("token"));
        assertEquals("GITHUB_OAUTH_NOT_CONFIGURED", exception.code());
    }
}
