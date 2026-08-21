package qg.qgent.sandboxworker.tool;

import java.util.List;

/**
 * Coding Agent 可调用的固定开发命令。枚举值与参数数组一一对应，禁止接收任意 argv、环境变量或工作目录。
 */
public enum DevelopmentCommandId {
    MAVEN_TEST(List.of("mvn", "--offline", "test")),
    MAVEN_PACKAGE(List.of("mvn", "--offline", "-DskipTests", "package")),
    MAVEN_WRAPPER_TEST(List.of("sh", "./mvnw", "--offline", "test")),
    GRADLE_TEST(List.of("gradle", "--offline", "--no-daemon", "test")),
    GRADLE_WRAPPER_TEST(List.of("sh", "./gradlew", "--offline", "--no-daemon", "test")),
    NPM_TEST(List.of("npm", "--offline", "test"));

    private final List<String> argv;

    DevelopmentCommandId(List<String> argv) {
        this.argv = argv;
    }

    public List<String> argv() {
        return argv;
    }
}
