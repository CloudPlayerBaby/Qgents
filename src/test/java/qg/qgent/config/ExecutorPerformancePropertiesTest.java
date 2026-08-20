package qg.qgent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutorPerformancePropertiesTest {

    @Test
    void defaultPoolsAreValid() {
        ExecutorPerformanceProperties properties = new ExecutorPerformanceProperties();

        assertDoesNotThrow(() -> properties.getOrchestration().validate("orchestration"));
        assertDoesNotThrow(() -> properties.getTestExecution().validate("test-execution"));
        assertDoesNotThrow(() -> properties.getTaskRunTimeout().validate("task-run-timeout"));
    }

    @Test
    void invalidPoolConfigurationFailsFast() {
        ExecutorPerformanceProperties.Pool pool = new ExecutorPerformanceProperties.Pool(4, 2, 100);

        assertThrows(IllegalArgumentException.class, () -> pool.validate("test"));
    }
}
