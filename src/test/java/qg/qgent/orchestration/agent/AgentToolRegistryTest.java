package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 角色→工具白名单门禁测试：写权限按 role 决定——仅 DEVELOPER 授予写工具 {@link CodingTools}，
 * 其余角色（含自定义角色）一律只读 {@link ReviewTools}（写权限默认拒绝）。
 */
class AgentToolRegistryTest {

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(codeAccess, writer);
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void writeRoleGrantsWriteTools() {
        assertThat(registry.hasWriteRole("DEVELOPER")).isTrue();
        assertThat(registry.toolsFor(workspaceId, "DEVELOPER")).isInstanceOf(CodingTools.class);
    }

    @Test
    void nonWriteRolesStayReadOnly() {
        assertThat(registry.hasWriteRole("TESTER")).isFalse();
        assertThat(registry.hasWriteRole("REVIEWER")).isFalse();
        assertThat(registry.hasWriteRole("PLANNER")).isFalse();
        assertThat(registry.hasWriteRole("CUSTOM")).isFalse();
        assertThat(registry.hasWriteRole(null)).isFalse();
        assertThat(registry.toolsFor(workspaceId, "CUSTOM")).isInstanceOf(ReviewTools.class);
        assertThat(registry.toolsFor(workspaceId, "TESTER")).isInstanceOf(ReviewTools.class);
        assertThat(registry.toolsFor(workspaceId, null)).isInstanceOf(ReviewTools.class);
    }
}
