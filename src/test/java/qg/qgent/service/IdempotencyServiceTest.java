package qg.qgent.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import qg.qgent.api.ApiException;
import qg.qgent.api.PersistedApiException;
import qg.qgent.dto.TeamResponse;
import qg.qgent.entity.IdempotencyRecordEntity;
import qg.qgent.mapper.IdempotencyRecordMapper;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    private final Object lock = new Object();
    private final AtomicReference<IdempotencyRecordEntity> stored = new AtomicReference<>();
    private IdempotencyRecordMapper mapper;
    private IdempotencyService service;
    private PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        mapper = mock(IdempotencyRecordMapper.class);
        when(mapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(mapper.selectById(any())).thenAnswer(invocation -> stored.get());
        when(mapper.insert(any(IdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            IdempotencyRecordEntity value = invocation.getArgument(0);
            synchronized (lock) {
                if (stored.get() != null) {
                    throw new DuplicateKeyException("duplicate");
                }
                value.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                stored.set(value);
            }
            return 1;
        });
        when(mapper.updateById(any(IdempotencyRecordEntity.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.completeClaim(any(UUID.class), any(byte[].class),
                org.mockito.ArgumentMatchers.anyInt(), any(String.class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    byte[] expectedHash = invocation.getArgument(1);
                    int responseStatus = invocation.getArgument(2);
                    String responseBody = invocation.getArgument(3);
                    IdempotencyRecordEntity current = stored.get();
                    if (current == null || !id.equals(current.getId()) || current.getResponseBodyRedacted() != null
                            || !MessageDigest.isEqual(current.getRequestHash(), expectedHash)) {
                        return 0;
                    }
                    current.setResponseStatus(responseStatus);
                    current.setResponseBodyRedacted(responseBody);
                    return 1;
                });
        when(mapper.deleteById(any(UUID.class))).thenAnswer(invocation -> {
            stored.set(null);
            return 1;
        });
        when(mapper.deletePendingClaim(any(UUID.class), any(byte[].class))).thenAnswer(invocation -> {
            IdempotencyRecordEntity current = stored.get();
            byte[] expected = invocation.getArgument(1);
            if (current != null && current.getResponseBodyRedacted() == null
                    && MessageDigest.isEqual(current.getRequestHash(), expected)) {
                stored.set(null);
                return 1;
            }
            return 0;
        });
        when(mapper.deleteStaleClaim(any(UUID.class), any(byte[].class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    IdempotencyRecordEntity current = stored.get();
                    byte[] expected = invocation.getArgument(1);
                    LocalDateTime cutoff = invocation.getArgument(2);
                    if (current != null && current.getResponseBodyRedacted() == null
                            && MessageDigest.isEqual(current.getRequestHash(), expected)
                            && !current.getCreatedAt().isAfter(cutoff)) {
                        stored.set(null);
                        return 1;
                    }
                    return 0;
                });
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new IdempotencyService(mapper, new ObjectMapper().findAndRegisterModules(), transactions);
    }

    @Test
    void concurrentSameRequestExecutesActionOnceAndReplaysResult() throws Exception {
        UUID actor = UUID.randomUUID();
        TeamResponse expected = new TeamResponse(UUID.randomUUID().toString(), "研发", "TEAM_OWNER");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.execute(actor, "POST:/teams", "key-1", Map.of("name", "研发"),
                    201, TeamResponse.class, () -> {
                        calls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return expected;
                    }));
            entered.await(2, TimeUnit.SECONDS);
            var second = pool.submit(() -> service.execute(actor, "POST:/teams", "key-1", Map.of("name", "研发"),
                    201, TeamResponse.class, () -> {
                        calls.incrementAndGet();
                        return expected;
                    }));
            release.countDown();

            assertEquals(expected, first.get(2, TimeUnit.SECONDS));
            assertEquals(expected, second.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void canonicalHashDoesNotDependOnMapInsertionOrder() {
        UUID actor = UUID.randomUUID();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("teamId", "1");
        first.put("name", "研发");
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("name", "研发");
        reversed.put("teamId", "1");
        TeamResponse expected = new TeamResponse(UUID.randomUUID().toString(), "研发", "TEAM_OWNER");

        service.execute(actor, "PATCH:/teams/{teamId}", "key-1", first, 200, TeamResponse.class, () -> expected);
        AtomicInteger calls = new AtomicInteger();
        TeamResponse replay = service.execute(actor, "PATCH:/teams/{teamId}", "key-1", reversed, 200,
                TeamResponse.class, () -> {
                    calls.incrementAndGet();
                    return expected;
                });

        assertEquals(expected, replay);
        assertEquals(0, calls.get());
    }

    @Test
    void actionFailureRemovesClaimAndAllowsRetry() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalStateException.class,
                () -> service.execute(actor, "POST:/teams", "key-1", Map.of("name", "研发"), 201,
                        TeamResponse.class, () -> {
                            throw new IllegalStateException("failed");
                        }));
        TeamResponse expected = new TeamResponse(UUID.randomUUID().toString(), "研发", "TEAM_OWNER");
        assertEquals(expected, service.execute(actor, "POST:/teams", "key-1", Map.of("name", "研发"), 201,
                TeamResponse.class, () -> expected));
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        UUID actor = UUID.randomUUID();
        TeamResponse expected = new TeamResponse(UUID.randomUUID().toString(), "研发", "TEAM_OWNER");
        service.execute(actor, "POST:/teams", "key-1", Map.of("name", "研发"), 201, TeamResponse.class,
                () -> expected);

        ApiException error = assertThrows(ApiException.class,
                () -> service.execute(actor, "POST:/teams", "key-1", Map.of("name", "市场"), 201,
                        TeamResponse.class, () -> expected));
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code());
    }

    @Test
    void cachesAndReplaysSafeApiError() {
        UUID actor = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        ApiException first = assertThrows(ApiException.class,
                () -> service.execute(actor, "PATCH:/teams/{teamId}", "key-error", Map.of("name", "研发"), 200,
                        TeamResponse.class, () -> {
                            calls.incrementAndGet();
                            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "TEAM_CONFLICT",
                                    "团队状态冲突");
                        }));
        ApiException replay = assertThrows(ApiException.class,
                () -> service.execute(actor, "PATCH:/teams/{teamId}", "key-error", Map.of("name", "研发"), 200,
                        TeamResponse.class, () -> {
                            calls.incrementAndGet();
                            return null;
                        }));

        assertEquals("TEAM_CONFLICT", first.code());
        assertEquals("TEAM_CONFLICT", replay.code());
        assertEquals(1, calls.get());
    }

    @Test
    void cachesAndReplaysCommittedExpiredError() {
        UUID actor = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        PersistedApiException first = assertThrows(PersistedApiException.class,
                () -> service.execute(actor, "POST:/team-invitations/{token}/accept", "key-expired",
                        Map.of("token", "opaque"), 200, TeamResponse.class, () -> {
                            calls.incrementAndGet();
                            throw new PersistedApiException(org.springframework.http.HttpStatus.CONFLICT,
                                    "INVITATION_EXPIRED", "邀请已过期");
                        }));
        ApiException replay = assertThrows(ApiException.class,
                () -> service.execute(actor, "POST:/team-invitations/{token}/accept", "key-expired",
                        Map.of("token", "opaque"), 200, TeamResponse.class, () -> {
                            calls.incrementAndGet();
                            return null;
                        }));

        assertEquals("INVITATION_EXPIRED", first.code());
        assertEquals("INVITATION_EXPIRED", replay.code());
        assertEquals(1, calls.get());
    }

    @Test
    void committedErrorRollsBackWhenLeaseWasTakenOver() {
        UUID actor = UUID.randomUUID();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.execute(actor, "POST:/team-invitations/{token}/accept", "key-taken-over",
                        Map.of("token", "opaque"), 200, TeamResponse.class, () -> {
                            IdempotencyRecordEntity replacement = new IdempotencyRecordEntity();
                            replacement.setId(UUID.randomUUID());
                            replacement.setRequestHash(stored.get().getRequestHash());
                            replacement.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                            replacement.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
                            stored.set(replacement);
                            throw new PersistedApiException(org.springframework.http.HttpStatus.CONFLICT,
                                    "INVITATION_EXPIRED", "邀请已过期");
                        }));

        assertEquals("幂等 lease 已被其他请求接管", error.getMessage());
        org.mockito.Mockito.verify(transactions, org.mockito.Mockito.atLeastOnce()).rollback(any());
    }

    @Test
    void staleClaimUsesConditionalDeleteBeforeTakeover() throws Exception {
        UUID actor = UUID.randomUUID();
        ObjectMapper canonical = new ObjectMapper().findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        byte[] requestHash = MessageDigest.getInstance("SHA-256")
                .digest(canonical.writeValueAsBytes(Map.of("name", "研发")));
        IdempotencyRecordEntity stale = new IdempotencyRecordEntity();
        stale.setId(UUID.randomUUID());
        stale.setRequestHash(requestHash);
        stale.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        stale.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(23));
        stored.set(stale);
        TeamResponse expected = new TeamResponse(UUID.randomUUID().toString(), "研发", "TEAM_OWNER");

        assertEquals(expected, service.execute(actor, "POST:/teams", "key-stale", Map.of("name", "研发"), 201,
                TeamResponse.class, () -> expected));
        org.mockito.Mockito.verify(mapper).deleteStaleClaim(any(UUID.class), any(byte[].class),
                any(LocalDateTime.class));
    }

    @Test
    void rejectsMissingKey() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.execute(UUID.randomUUID(), "POST:/teams", null, new Object(), 201,
                        TeamResponse.class, () -> null));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", error.code());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
