package qg.qgent.orchestration.agent;

import org.springframework.stereotype.Service;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.List;

/**
 * Agent 角色 → 工具白名单注册表：决定自定义 Agent 能拿到哪些工具。结构即权限，编译期可审计。
 * <ul>
 *   <li>只读工具 {@link ReviewTools}（list_files/read_file/search_code）恒有；</li>
 *   <li>写工具 {@link CodingTools}（含 apply_patch/replace_file/write_file/create_directory）仅当角色具备写权限才授予——
 *       按 role 决定（{@code DEVELOPER} 授写，其余角色默认只读）；</li>
 *   <li>自定义角色除非显式声明为写角色，否则一律只读（写权限默认拒绝）。</li>
 * </ul>
 * {@link #toolsFor} 返回的 {@link CodingTools} 或 {@link ReviewTools} 均是以 Spring AI
 * {@code @Tool} 注解声明的 POJO，可经 {@code ToolCallbacks.from} 解析为原生函数 schema。
 */
@Service
public class AgentToolRegistry {

    /**
     * 具备写代码权限的角色；默认只授 DEVELOPER，其余角色（含自定义角色）只读。
     */
    private static final Set<String> WRITE_ROLES = Set.of("DEVELOPER");

    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;

    public AgentToolRegistry(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.codeAccess = codeAccess;
        this.writer = writer;
    }

    /**
     * 角色是否具备写权限（写权限默认拒绝：非写角色一律只读）。
     */
    public boolean hasWriteRole(String role) {
        return role != null && WRITE_ROLES.contains(role);
    }

    /**
     * 按角色构建工具集：读工具恒有，写工具仅当角色具备写权限。
     */
    public Object toolsFor(UUID workspaceId, String role) {
        return toolsFor(workspaceId, role, hasWriteRole(role));
    }

    /**
     * 按角色和已冻结的步骤写权限构建工具集。步骤策略优先于角色，
     * 例如 DEVELOPER 角色执行 VERIFY 步骤时仍必须是只读。
     */
    public Object toolsFor(UUID workspaceId, String role, boolean allowWrite) {
        return toolsFor(workspaceId, role, allowWrite, List.of());
    }

    /**
     * Builds a step-scoped tool set. Read tools remain workspace-wide; the
     * write tools receive the immutable TaskStep path policy.
     */
    public Object toolsFor(UUID workspaceId, String role, boolean allowWrite,
                           Collection<String> allowedPaths) {
        return allowWrite && hasWriteRole(role)
                ? new CodingTools(workspaceId, codeAccess, writer, allowedPaths)
                : new ReviewTools(workspaceId, codeAccess);
    }
}
