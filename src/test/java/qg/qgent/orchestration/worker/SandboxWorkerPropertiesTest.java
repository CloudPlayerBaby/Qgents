package qg.qgent.orchestration.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxWorkerPropertiesTest {

    @Test
    void acceptsPositiveLeaseRenewInterval() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setLeaseRenewInterval(Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(5), properties.leaseRenewInterval());
    }

    @Test
    void storesBackendServiceToken() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();

        properties.setBackendServiceToken("shared-secret");

        assertEquals("shared-secret", properties.getBackendServiceToken());
    }

    @Test
    void rejectsNonPositiveLeaseRenewInterval() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setLeaseRenewInterval(Duration.ZERO);

        assertThrows(IllegalStateException.class, properties::leaseRenewInterval);
    }

    @Test
    void rejectsInvalidAcquireRetryConfiguration() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setAcquireMaxAttempts(0);
        assertThrows(IllegalStateException.class, properties::acquireMaxAttempts);

        properties.setAcquireMaxAttempts(1);
        properties.setAcquireInitialBackoff(Duration.ofSeconds(-1));
        assertThrows(IllegalStateException.class, properties::acquireInitialBackoff);
    }
}
