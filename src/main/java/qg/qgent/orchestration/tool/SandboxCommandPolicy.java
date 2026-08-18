package qg.qgent.orchestration.tool;

import java.util.List;
import java.util.Set;

/**
 * 执行命令白名单策略：对完整命令向量做精确匹配，只放行与测试相关的固定构建命令模板。
 * <p>
 * 安全约束（执行层的独立防线，即使未来其他调用方传入任意命令也会在端口内被拦截）：
 * <ul>
 *   <li>仅允许系统构建工具、工作区相对路径 Wrapper 和 {@code npm test} 的固定模板，
 *       二进制与参数都必须完全一致；</li>
 *   <li>暂不支持 {@code -Dtest=}、{@code -DskipTests} 或任何额外参数、自定义命令；</li>
 *   <li>null / 空向量 / 未知二进制 / 多参数一律拒绝，从结构上排除 rm、sudo、curl、git 等
 *       危险操作与 shell 拼接。</li>
 * </ul>
 */
final class SandboxCommandPolicy {

    private static final Set<List<String>> ALLOWED = Set.of(
            List.of("mvn", "test"),
            List.of("mvnw", "test"),
            List.of("mvnw.cmd", "test"),
            List.of("./mvnw", "test"),
            List.of("./mvnw.cmd", "test"),
            List.of("gradle", "test"),
            List.of("gradlew", "test"),
            List.of("gradlew.bat", "test"),
            List.of("gradlew.cmd", "test"),
            List.of("./gradlew", "test"),
            List.of("./gradlew.bat", "test"),
            List.of("./gradlew.cmd", "test"),
            List.of("npm", "test"));

    /**
     * 判断命令向量是否命中白名单模板。
     *
     * @param command 命令与参数；null 或包含任意非白名单元素时返回 false。
     * @return true 表示允许执行，false 表示拒绝。
     */
    boolean allows(List<String> command) {
        return command != null && ALLOWED.contains(command);
    }
}
