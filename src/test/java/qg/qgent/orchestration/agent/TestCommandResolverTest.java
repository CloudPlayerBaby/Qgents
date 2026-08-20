package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCommandResolverTest {

    private final TestCommandResolver resolver = new TestCommandResolver();

    @Test
    void usesWorkspaceRelativeMavenWrapper() {
        assertThat(resolver.resolve(List.of("pom.xml", "mvnw")))
                .containsExactly("sh", "./mvnw", "test");
    }

    @Test
    void usesWorkspaceRelativeGradleWrapper() {
        assertThat(resolver.resolve(List.of("build.gradle", "gradlew")))
                .containsExactly("sh", "./gradlew", "test");
    }

    @Test
    void fallsBackToInstalledBuildToolWhenWrapperIsAbsent() {
        assertThat(resolver.resolve(List.of("pom.xml")))
                .containsExactly("mvn", "test");
        assertThat(resolver.resolve(List.of("build.gradle")))
                .containsExactly("gradle", "test");
    }

    @Test
    void treatsWindowsOnlyWrappersAsAbsentInLinuxSandbox() {
        assertThat(resolver.resolveCommand(List.of("backend/pom.xml", "backend/mvnw.cmd")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("mvn", "test"), "backend"));
        assertThat(resolver.resolveCommand(List.of("frontend/build.gradle", "frontend/gradlew.cmd")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("gradle", "test"), "frontend"));
    }

    @Test
    void bindsMultiRepositoryGradleWrapperToTheMatchingRepository() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "frontend/package.json", "backend/build.gradle.kts", "backend/gradlew"));

        assertThat(command.repositoryPath()).isEqualTo("backend");
        assertThat(command.command()).containsExactly("sh", "./gradlew", "test");
    }

    @Test
    void bindsNestedRepositoryPathWithoutTruncatingAtFirstSegment() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "services/backend/pom.xml", "services/backend/mvnw", "services/backend/src/Main.java",
                "web/package.json"));

        assertThat(command.repositoryPath()).isEqualTo("services/backend");
        assertThat(command.command()).containsExactly("sh", "./mvnw", "test");
    }

    @Test
    void ignoresUnrelatedGradleWorkspaceForNodeTarget() {
        assertThat(resolver.resolveCommand(List.of("gradlew", "build.gradle", "hello.js"), List.of("hello.js")))
                .isNull();
    }

    @Test
    void ignoresUnrelatedGradleWorkspaceForDocumentationTarget() {
        assertThat(resolver.resolveCommand(List.of("gradlew", "build.gradle", "README.md"), List.of("README.md")))
                .isNull();
    }

    @Test
    void keepsGradleForJvmTarget() {
        assertThat(resolver.resolveCommand(List.of("gradlew", "build.gradle", "src/Main.java"), List.of("src/Main.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("sh", "./gradlew", "test"), null));
    }

    @Test
    void runsNodeTestFileWhenPackageJsonIsAbsent() {
        // 无 package.json 但存在 tests/*.test.js：直接 node 执行（Planner 要求 node tests/todo.test.js）。
        assertThat(resolver.resolveCommand(List.of("tests/todo.test.js", "src/todo.js")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("node", "tests/todo.test.js"), null));
    }

    @Test
    void runsNodeSpecFileWhenPackageJsonIsAbsent() {
        assertThat(resolver.resolveCommand(List.of("tests/calc.spec.js", "src/calc.js")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("node", "tests/calc.spec.js"), null));
    }

    @Test
    void bindsNodeTestFileToItsRepositoryInMultiRepoWorkspace() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "frontend/package.json", "backend/tests/api.test.js", "backend/src/api.js"));

        // package.json 优先于裸 node 文件：frontend 命中 npm test。
        assertThat(command.repositoryPath()).isEqualTo("frontend");
        assertThat(command.command()).containsExactly("npm", "test");
    }

    @Test
    void bindsBareNodeTestFileToRepositoryWithoutPackageJson() {
        TestCommandResolver.ResolvedCommand command = resolver.resolveCommand(List.of(
                "svc-a/src/a.js", "svc-a/tests/a.test.js", "svc-b/src/b.js"));

        assertThat(command.repositoryPath()).isEqualTo("svc-a");
        assertThat(command.command()).containsExactly("node", "tests/a.test.js");
    }

    @Test
    void ignoresNonTestNodeFilesUnderTestsDirectory() {
        assertThat(resolver.resolveCommand(List.of("tests/util.js", "src/todo.js")))
                .isNull();
    }

    @Test
    void prefersPackageJsonTestOverBareNodeTestFile() {
        assertThat(resolver.resolveCommand(List.of("package.json", "tests/todo.test.js", "src/todo.js")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("npm", "test"), null));
    }
}
