package qg.qgent.orchestration.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 验证 {@link SandboxWorkerConfiguration} 在提供 {@link ObjectMapper} 的情况下
 * 能创建 {@link SandboxWorkerClient} 与 {@link SandboxWorkerProperties} Bean，
 * 覆盖新增 Spring Bean 装配的上下文创建检查，不依赖 MySQL / Redis 等外部设施。
 */
class SandboxWorkerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(SandboxWorkerConfiguration.class);

    @Test
    void createsWorkerClientAndPropertiesBeans() {
        runner.run(context -> {
            assertNotNull(context.getBean(SandboxWorkerClient.class));
            assertNotNull(context.getBean(SandboxWorkerProperties.class));
        });
    }
}
