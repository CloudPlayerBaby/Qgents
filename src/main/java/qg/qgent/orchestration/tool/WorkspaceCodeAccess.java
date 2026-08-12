package qg.qgent.orchestration.tool;

import java.util.List;
import java.util.UUID;

/**
 * Agent 对 Workspace 代码的只读访问端口（list_files / read_file / search_code）。
 * <p>
 * 接口刻意只暴露读取能力：没有创建、修改、删除或执行接口，从结构上保证实现方
 * （当前为本地最小实现，后续替换为真实 Sandbox）无法被 Plan Agent 用于改写代码。
 * 所有路径均为相对路径，禁止向调用方暴露宿主机绝对路径。
 */
public interface WorkspaceCodeAccess {

    /**
     * 列出 Workspace 中所有代码文件（相对路径，已排除 .git、target 等目录）。
     *
     * @param workspaceId 目标 Workspace。
     * @return 排序后的相对路径列表；目录不存在或不可用时返回空列表。
     */
    List<String> listFiles(UUID workspaceId);

    /**
     * 读取单个文件的文本内容。
     *
     * @param workspaceId 目标 Workspace。
     * @param path        相对路径。
     * @return UTF-8 文本内容；路径越界、文件不存在或读取失败时返回 null。
     */
    String readFile(UUID workspaceId, String path);

    /**
     * 在 Workspace 代码中检索包含指定关键字（忽略大小写）的文件。
     *
     * @param workspaceId 目标 Workspace。
     * @param query       检索关键字。
     * @return 命中的相对路径列表；目录不可用或查询为空时返回空列表。
     */
    List<String> searchCode(UUID workspaceId, String query);
}
