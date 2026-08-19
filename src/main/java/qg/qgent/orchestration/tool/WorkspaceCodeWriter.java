package qg.qgent.orchestration.tool;

import java.util.UUID;

/**
 * 对 Workspace 的受控写入端口。与只读端口 {@link WorkspaceCodeAccess} 分离，
 * 只有持有本端口的 Agent（CodingAgent）才被允许修改代码，PlanAgent 不会获得写能力。
 * <p>
 * 实现必须保证：路径限制在 Workspace 根目录内、拒绝路径穿越、限制单文件大小、
 * 失败时返回明确错误。当前本地实现写入 {@code app.workspace.base-dir}/{storageKey}，
 * 真实 Sandbox 接入后由沙箱内实现替换。
 */
public interface WorkspaceCodeWriter {

    /**
     * 递归创建 Workspace 内目录；目标目录已存在时幂等成功。
     *
     * @param workspaceId 目标 Workspace。
     * @param path Workspace 相对目录路径。
     * @return 目录创建结果；不生成 .gitkeep。
     */
    WorkspaceDirectoryResult createDirectory(UUID workspaceId, String path);

    /**
     * 覆盖写入 Workspace 内的一个文件；父目录不存在时按实现约定自动创建。
     *
     * @param workspaceId 目标 Workspace。
     * @param path        相对路径。
     * @param content     UTF-8 文本内容。
     * @return 结构化结果：成功给出写入路径，失败给出可回灌给 LLM 的错误说明。
     */
    WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content);

    /**
     * 对 Workspace 内已有 UTF-8 文本文件精确应用统一 Diff（unified diff）局部修改；
     * expectedHash 必须来自同一次 {@link WorkspaceCodeAccess#readFile} 返回的 sha256，
     * 与当前文件原始字节不一致时拒绝应用且不产生任何写入。
     *
     * @param workspaceId  目标 Workspace。
     * @param path         相对路径。
     * @param expectedHash 目标文件当前内容原始字节的 64 位十六进制 SHA-256。
     * @param patch        统一 Diff 文本（UTF-8，最长 1 MiB）。
     * @return 结构化结果：成功给出写入路径，失败给出可回灌给 LLM 的错误说明。
     */
    WorkspaceWriteResult patchFile(UUID workspaceId, String path, String expectedHash, String patch);
}
