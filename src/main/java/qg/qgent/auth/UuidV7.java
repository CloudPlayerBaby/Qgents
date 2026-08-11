package qg.qgent.auth;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 生成器
 */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long randomA = RANDOM.nextInt(1 << 12);
        long mostSignificant = (timestamp << 16) | 0x7000L | randomA;
        long leastSignificant = RANDOM.nextLong();
        leastSignificant = (leastSignificant & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
