package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 能力→工具白名单门禁测试：写能力默认拒绝，仅 capabilities 含写能力（coding/implementation/write，
 * 大小写不敏感）时授予写工具 {@link CodingTools}，否则一律只读 {@link ReviewTools}。
 */
class CapabilityToolRegistryTest {

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final CapabilityToolRegistry registry = new CapabilityToolRegistry(codeAccess, writer);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void nullOrEmptyCapabilitiesAreReadOnly() {
        assertThat(registry.hasWriteCapability(null)).isFalse();
        assertThat(registry.hasWriteCapability(List.of())).isFalse();
    }

    @Test
    void writeCapabilitiesGrantWriteTools() {
        assertThat(registry.hasWriteCapability(List.of("coding", "implementation"))).isTrue();
        assertThat(registry.hasWriteCapability(List.of("write"))).isTrue();
        // 大小写不敏感
        assertThat(registry.hasWriteCapability(List.of("Coding"))).isTrue();
    }

    @Test
    void nonWriteCapabilitiesStayReadOnly() {
        assertThat(registry.hasWriteCapability(List.of("read", "test", "review"))).isFalse();
        assertThat(registry.hasWriteCapability(List.of("unknown"))).isFalse();
    }

    @Test
    void toolsForMapsCapabilitiesToToolSet() {
        assertThat(registry.toolsFor(workspaceId, List.of("coding"))).isInstanceOf(CodingTools.class);
        assertThat(registry.toolsFor(workspaceId, List.of("read"))).isInstanceOf(ReviewTools.class);
        assertThat(registry.toolsFor(workspaceId, null)).isInstanceOf(ReviewTools.class);
    }
}
