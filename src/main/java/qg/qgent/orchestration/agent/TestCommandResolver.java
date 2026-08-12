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
        if (names.contains("pom.xml")) {
            return names.contains("mvnw") || names.contains("mvnw.cmd")
                    ? List.of("mvnw", "test")
                    : List.of("mvn", "test");
        }
        if (names.contains("build.gradle") || names.contains("build.gradle.kts")
                || names.contains("settings.gradle")) {
            return names.contains("gradlew") || names.contains("gradlew.cmd")
                    ? List.of("gradlew", "test")
                    : List.of("gradle", "test");
        }
        if (names.contains("package.json")) {
            return List.of("npm", "test");
        }
        return null;
    }
}
