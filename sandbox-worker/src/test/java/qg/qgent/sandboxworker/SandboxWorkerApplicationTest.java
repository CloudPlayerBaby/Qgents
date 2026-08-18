package qg.qgent.sandboxworker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import qg.qgent.sandboxworker.persistence.ToolExecutionLogMapper;
import qg.qgent.sandboxworker.persistence.ToolExecutionMapper;

/** 验证独立工作节点可以创建 Spring 应用上下文。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "sandbox.runtime=fake")
class SandboxWorkerApplicationTest {
    @MockitoBean
    private ToolExecutionMapper toolExecutionMapper;

    @MockitoBean
    private ToolExecutionLogMapper toolExecutionLogMapper;
    @Test
    void contextLoads() {
    }
}
