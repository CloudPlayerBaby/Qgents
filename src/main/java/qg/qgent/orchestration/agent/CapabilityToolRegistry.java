package qg.qgent.orchestration.agent;

import org.springframework.stereotype.Service;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 能力标签 → 工具白名单注册表：决定自定义 Agent 能拿到哪些工具。结构即权限，编译期可审计。
 * <ul>
 *   <li>只读工具 {@link ReviewTools}（list_files/read_file/search_code）恒有；</li>
 *   <li>写工具 {@link CodingTools}（含 apply_patch/write_file）仅当 capabilities 含写能力
 *       （coding/implementation/write）才授予——写能力默认拒绝；</li>
 *   <li>未知/空 capabilities 一律只读。预置 DEVELOPER 的 [coding, implementation] 命中→写；
 *       TESTER/REVIEWER→只读。</li>
 * </ul>
 * {@link #toolsFor} 返回的 {@link CodingTools} 或 {@link ReviewTools} 均是以 Spring AI
 * {@code @Tool} 注解声明的 POJO，可经 {@code ToolCallbacks.from} 解析为原生函数 schema。
 */
@Service
public class CapabilityToolRegistry {

    private static final Set<String> WRITE_CAPABILITIES = Set.of("coding", "implementation", "write");

    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;

    public CapabilityToolRegistry(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.codeAccess = codeAccess;
        this.writer = writer;
    }

    /**
     * 是否授予写工具（写能力默认拒绝：未知/空 capabilities 一律只读）。
     */
    public boolean hasWriteCapability(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        return capabilities.stream()
                .anyMatch(capability -> capability != null
                        && WRITE_CAPABILITIES.contains(capability.toLowerCase(Locale.ROOT)));
    }

    /**
     * 按能力构建工具集：读工具恒有，写工具仅当含写能力。
     */
    public Object toolsFor(UUID workspaceId, List<String> capabilities) {
        return hasWriteCapability(capabilities)
                ? new CodingTools(workspaceId, codeAccess, writer)
                : new ReviewTools(workspaceId, codeAccess);
    }
}
