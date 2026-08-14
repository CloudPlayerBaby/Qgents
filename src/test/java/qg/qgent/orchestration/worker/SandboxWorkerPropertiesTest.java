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
    void rejectsNonPositiveLeaseRenewInterval() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setLeaseRenewInterval(Duration.ZERO);

        assertThrows(IllegalStateException.class, properties::leaseRenewInterval);
    }
}
