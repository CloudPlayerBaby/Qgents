package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SandboxCommandPolicy 纯单元测试：白名单 8 个测试模板放行，危险命令、额外参数、
 * 自定义命令、null/空向量全部拒绝。无 OS 依赖，不执行任何命令。
 */
class SandboxCommandPolicyTest {

    private final SandboxCommandPolicy policy = new SandboxCommandPolicy();

    @Test
    void allowsAllWhitelistedTestTemplates() {
        assertThat(policy.allows(List.of("mvn", "test"))).isTrue();
        assertThat(policy.allows(List.of("mvnw", "test"))).isTrue();
        assertThat(policy.allows(List.of("mvnw.cmd", "test"))).isTrue();
        assertThat(policy.allows(List.of("gradle", "test"))).isTrue();
        assertThat(policy.allows(List.of("gradlew", "test"))).isTrue();
        assertThat(policy.allows(List.of("gradlew.bat", "test"))).isTrue();
        assertThat(policy.allows(List.of("gradlew.cmd", "test"))).isTrue();
        assertThat(policy.allows(List.of("npm", "test"))).isTrue();
    }

    @Test
    void rejectsNullAndEmptyCommand() {
        assertThat(policy.allows(null)).isFalse();
        assertThat(policy.allows(List.of())).isFalse();
    }

    @Test
    void rejectsExtraOrMissingArguments() {
        assertThat(policy.allows(List.of("mvn"))).isFalse();
        assertThat(policy.allows(List.of("mvn", "test", "-DskipTests"))).isFalse();
        assertThat(policy.allows(List.of("mvn", "-Dtest=X", "test"))).isFalse();
        assertThat(policy.allows(List.of("mvn", "clean", "test"))).isFalse();
        assertThat(policy.allows(List.of("npm", "install"))).isFalse();
        assertThat(policy.allows(List.of("npm", "run", "test"))).isFalse();
    }

    @Test
    void rejectsDangerousCommands() {
        assertThat(policy.allows(List.of("rm", "-rf", "/"))).isFalse();
        assertThat(policy.allows(List.of("sudo", "rm", "-rf", "/"))).isFalse();
        assertThat(policy.allows(List.of("curl", "http://evil"))).isFalse();
        assertThat(policy.allows(List.of("wget", "http://evil"))).isFalse();
        assertThat(policy.allows(List.of("git", "push"))).isFalse();
        assertThat(policy.allows(List.of("cmd", "/c", "rm"))).isFalse();
        assertThat(policy.allows(List.of("sh", "-c", "mvn test"))).isFalse();
        assertThat(policy.allows(List.of("mvn", "test", "&&", "rm", "-rf", "/"))).isFalse();
    }

    @Test
    void rejectsUnknownBinaryAndCaseVariants() {
        assertThat(policy.allows(List.of("make", "test"))).isFalse();
        assertThat(policy.allows(List.of("MVN", "test"))).isFalse();
        assertThat(policy.allows(List.of("mvn", "TEST"))).isFalse();
    }
}
