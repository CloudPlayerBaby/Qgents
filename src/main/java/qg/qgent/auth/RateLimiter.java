package qg.qgent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiter {
    private static final Logger log= LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, LocalWindow> fallback = new ConcurrentHashMap<>();
    public RateLimiter(StringRedisTemplate redis) { this.redis=redis; }
    public boolean allow(String scope, String fingerprint, int limit, Duration window) {
        String key="rate:"+scope+":"+fingerprintHash(fingerprint);
        try {
            Long value=redis.opsForValue().increment(key);
            if (value != null && value == 1) redis.expire(key, window);
            return value == null || value <= limit;
        } catch (RuntimeException e) {
            log.warn("Redis rate limiter unavailable; using local fallback for scope={}", scope);
            return allowLocal(key, limit, window);
        }
    }
    private boolean allowLocal(String key, int limit, Duration window) {
        long now=System.currentTimeMillis();
        LocalWindow current=fallback.compute(key, (ignored, old) -> {
            if (old == null || old.expiresAt < now) return new LocalWindow(new AtomicInteger(1), now+window.toMillis());
            old.count.incrementAndGet(); return old;
        });
        if (fallback.size()>10_000) fallback.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        return current.count.get()<=limit;
    }
    private String fingerprintHash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record LocalWindow(AtomicInteger count, long expiresAt) {}
}
