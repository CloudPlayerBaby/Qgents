package qg.qgent.orchestration.agent;

import java.util.List;
import java.util.Set;

/**
 * 安全测试命令解析器：依据工作区文件树检测构建工具，返回白名单命令模板。
 * <p>
 * LLM 不参与命令选择，本类只允许返回硬编码的、与测试相关的构建命令，
 * 从结构上排除 rm / sudo / curl / wget / ssh / git push 等与测试无关的危险命令。
 * 检测不到受支持构建工具时返回 null，调用方不得执行任何命令。
 * 当前策略：Maven（pom.xml）、Gradle（build.gradle* / settings.gradle）、Node（package.json）。
 */
public class TestCommandResolver {

    /**
     * 依据文件列表解析安全的测试命令。
     *
     * @param files 工作区文件相对路径列表。
     * @return 白名单命令模板；无受支持构建工具时返回 null。
     */
    public List<String> resolve(List<String> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        Set<String> names = Set.copyOf(files);
        if (hasFile(names, "pom.xml")) {
            return hasFile(names, "mvnw") || hasFile(names, "mvnw.cmd")
                    ? List.of("mvnw", "test")
                    : List.of("mvn", "test");
        }
        if (hasFile(names, "build.gradle") || hasFile(names, "build.gradle.kts")
                || hasFile(names, "settings.gradle")) {
            return hasFile(names, "gradlew") || hasFile(names, "gradlew.cmd")
                    ? List.of("gradlew", "test")
                    : List.of("gradle", "test");
        }
        if (hasFile(names, "package.json")) {
            return List.of("npm", "test");
        }
        return null;
    }

    /**
     * 判断文件列表是否包含指定文件名。文件路径可能带 workspace 前缀（如 repo-1/package.json），
     * 因此同时匹配精确文件名与以 "/文件名" 结尾的路径。
     */
    private boolean hasFile(Set<String> files, String name) {
        return files.stream().anyMatch(f -> f.equals(name) || f.endsWith("/" + name));
    }
}
