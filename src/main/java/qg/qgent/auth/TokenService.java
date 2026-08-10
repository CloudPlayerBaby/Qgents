package qg.qgent.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class TokenService {
    private final Algorithm algorithm;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Duration resetTtl;
    private final SecureRandom random = new SecureRandom();

    public TokenService(@Value("${app.jwt-secret}") String secret,
            @Value("${app.access-token-minutes:15}") long accessMinutes,
            @Value("${app.refresh-token-days:30}") long refreshDays,
            @Value("${app.reset-token-minutes:30}") long resetMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET至少需要32字节");
        algorithm = Algorithm.HMAC256(secret);
        accessTtl = Duration.ofMinutes(accessMinutes);
        refreshTtl = Duration.ofDays(refreshDays);
        resetTtl = Duration.ofMinutes(resetMinutes);
    }

    public String access(UUID userId) {
        Instant now = Instant.now();
        return JWT.create().withIssuer("qgents").withAudience("qgents-api").withJWTId(UUID.randomUUID().toString())
                .withSubject(userId.toString()).withIssuedAt(now).withExpiresAt(now.plus(accessTtl)).sign(algorithm);
    }

    public UUID verifyAccess(String token) {
        try {
            return UUID.fromString(JWT.require(algorithm).withIssuer("qgents").withAudience("qgents-api").build()
                    .verify(token).getSubject());
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return null;
        }
    }

    public String opaque() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Instant refreshExpiry() {
        return Instant.now().plus(refreshTtl);
    }

    public Instant resetExpiry() {
        return Instant.now().plus(resetTtl);
    }

    public long accessSeconds() {
        return accessTtl.toSeconds();
    }

    public long refreshSeconds() {
        return refreshTtl.toSeconds();
    }
}
