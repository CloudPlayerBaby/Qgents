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
    void selectsNpmTestForFrontendTargetWhenBackendMavenAlsoExists() {
        assertThat(resolver.resolveCommand(List.of("backend/pom.xml", "backend/src/Main.java",
                        "frontend/package.json", "frontend/src/App.tsx"), List.of("frontend/src/App.tsx")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("npm", "test"), "frontend"));
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
    void keepsMavenMultiModuleAtRepositoryScope() {
        assertThat(resolver.resolveCommand(List.of("pom.xml", "services/backend/pom.xml",
                        "services/backend/src/Main.java"), List.of("services/backend/src/Main.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("mvn", "test"), null));
    }

    @Test
    void keepsMavenWrapperMultiModuleAtRepositoryScope() {
        assertThat(resolver.resolveCommand(List.of("pom.xml", "mvnw", "services/backend/pom.xml",
                        "services/backend/src/Main.java"), List.of("services/backend/src/Main.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("sh", "./mvnw", "test"), null));
    }

    @Test
    void fallsBackToFullMavenTestWhenTargetsSpanMultipleModules() {
        assertThat(resolver.resolveCommand(List.of("pom.xml", "services/backend/pom.xml",
                        "services/frontend/pom.xml", "services/backend/src/Main.java",
                        "services/frontend/src/App.java"),
                List.of("services/backend/src/Main.java", "services/frontend/src/App.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("mvn", "test"), null));
    }

    @Test
    void keepsFullMavenTestForSingleModuleRepository() {
        // 仓库根本身是唯一 pom：targets 下没有嵌套模块，保持整仓库命令。
        assertThat(resolver.resolveCommand(List.of("services/backend/pom.xml", "services/backend/mvnw",
                        "services/backend/src/Main.java", "web/package.json"),
                List.of("services/backend/src/Main.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(
                        List.of("sh", "./mvnw", "test"), "services/backend"));
    }

    @Test
    void keepsGradleMultiProjectAtRepositoryScope() {
        assertThat(resolver.resolveCommand(List.of("settings.gradle", "gradlew",
                        "services/backend/build.gradle", "services/backend/src/Main.java"),
                List.of("services/backend/src/Main.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("sh", "./gradlew", "test"), null));
    }

    @Test
    void fallsBackToFullGradleTestWhenTargetsSpanMultipleProjects() {
        assertThat(resolver.resolveCommand(List.of("settings.gradle",
                        "services/backend/build.gradle", "services/frontend/build.gradle",
                        "services/backend/src/Main.java", "services/frontend/src/App.java"),
                List.of("services/backend/src/Main.java", "services/frontend/src/App.java")))
                .isEqualTo(new TestCommandResolver.ResolvedCommand(List.of("gradle", "test"), null));
    }

    @Test
    void doesNotRunBareNodeTestFileWhenPackageJsonIsAbsent() {
        assertThat(resolver.resolveCommand(List.of("tests/todo.test.js", "src/todo.js")))
                .isNull();
    }

    @Test
    void doesNotRunBareNodeSpecFileWhenPackageJsonIsAbsent() {
        assertThat(resolver.resolveCommand(List.of("tests/calc.spec.js", "src/calc.js")))
                .isNull();
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
    void doesNotBindBareNodeTestFileWithoutPackageJson() {
        assertThat(resolver.resolveCommand(List.of("svc-a/src/a.js", "svc-a/tests/a.test.js", "svc-b/src/b.js")))
                .isNull();
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

    // ---------- Plan 结构化验证命令白名单校验 ----------

    @Test
    void allowsWhitelistedVerificationTemplates() {
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("mvn", "test"))).isTrue();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("sh", "./mvnw", "test"))).isTrue();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("gradle", "test"))).isTrue();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("sh", "./gradlew", "test"))).isTrue();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("npm", "test"))).isTrue();
    }

    @Test
    void rejectsArbitraryShellVerificationCommands() {
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("rm", "-rf", "/"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("curl", "http://evil"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("bash", "-c", "echo pwned"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("mvn", "clean", "install"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("npm", "run", "build"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("node", "tests/todo.test.js"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("node", "src/app.js"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of("node", "tests/util.js"))).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(List.of())).isFalse();
        assertThat(TestCommandResolver.isAllowedVerificationCommand(null)).isFalse();
    }
}
